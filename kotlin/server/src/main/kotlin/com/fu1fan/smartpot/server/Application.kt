package com.fu1fan.smartpot.server

import com.fu1fan.smartpot.server.catalog.SpeciesCatalog
import com.fu1fan.smartpot.server.service.*
import com.fu1fan.smartpot.server.store.InMemorySmartPotStore
import com.fu1fan.smartpot.server.store.PostgresSmartPotStore
import com.fu1fan.smartpot.server.store.SmartPotStore
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.seconds

fun main() {
    val config = AppConfig.fromEnvironment()
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { module(config) }.start(wait = true)
}

fun Application.module(
    config: AppConfig = AppConfig.fromEnvironment(),
    storeOverride: SmartPotStore? = null,
    startMqtt: Boolean = System.getenv("MQTT_ENABLED")?.toBooleanStrictOrNull() ?: true,
) {
    install(ContentNegotiation) { json(appJson) }
    install(WebSockets) { pingPeriod = 20.seconds; timeout = 45.seconds; maxFrameSize = 1_048_576 }
    install(CallLogging) { level = Level.INFO }
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Patch)
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, error -> call.respond(HttpStatusCode.BadRequest, com.fu1fan.smartpot.protocol.ApiError("bad_request", error.message ?: "请求无效")) }
        exception<Throwable> { call, error ->
            this@module.environment.log.error("Unhandled request failure", error)
            call.respond(HttpStatusCode.InternalServerError, com.fu1fan.smartpot.protocol.ApiError("internal_error", "服务器处理失败"))
        }
    }

    val shares = ShareTokenService(config.shareTokenSecret)
    install(Authentication) {
        bearer("access") {
            authenticate { credential ->
                if (credential.token == config.demoToken || shares.verify(credential.token) != null) UserIdPrincipal(credential.token) else null
            }
        }
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = storeOverride ?: config.databaseUrl?.let { PostgresSmartPotStore(config) } ?: InMemorySmartPotStore()
    runBlocking { store.seedSpecies(SpeciesCatalog.all) }
    val realtime = RealtimeHub()
    val pots = PotService(store, realtime)
    val affinity = AffinityService(store, realtime)
    val alerts = AlertService(store, realtime, affinity)
    val care = CareService(store, affinity)
    val ai = CloudAiService(config, store)
    val diary = DiaryService(store, ai, realtime)
    val maintenance = MaintenanceService(store)
    val mqtt = MqttGateway(config, scope, store, pots, alerts, affinity, realtime)
    val commands = CommandService(store, mqtt, realtime)
    mqtt.commandService = commands
    if (startMqtt) mqtt.start()
    diary.start(scope)
    maintenance.start(scope)
    configureRoutes(ServerServices(config, store, pots, care, ai, diary, commands, realtime, shares))

    monitor.subscribe(ApplicationStopped) {
        mqtt.close()
        ai.close()
        store.close()
        scope.cancel()
    }
}
