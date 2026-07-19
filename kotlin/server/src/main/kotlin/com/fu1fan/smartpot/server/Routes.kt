package com.fu1fan.smartpot.server

import com.fu1fan.smartpot.protocol.*
import com.fu1fan.smartpot.server.service.*
import com.fu1fan.smartpot.server.store.SmartPotStore
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.roundToInt

data class ServerServices(
    val config: AppConfig,
    val store: SmartPotStore,
    val pots: PotService,
    val care: CareService,
    val ai: CloudAiService,
    val weather: WeatherService,
    val diary: DiaryService,
    val commands: CommandService,
    val realtime: RealtimeHub,
    val shares: ShareTokenService,
)

fun Application.configureRoutes(services: ServerServices) {
    routing {
        get("/health") { call.respond(mapOf("status" to "ok", "service" to "smart-pot-server")) }
        post("/api/v1/share/redeem") {
            val request = call.receive<RedeemShareRequest>()
            require(request.actorName.isNotBlank() && request.actorName.length <= 24) { "访客昵称应为 1-24 个字符" }
            val now = Instant.now()
            val redeemed = requireNotNull(services.store.redeemShareCode(request.code.trim(), request.actorName.trim(), now.toString())) { "分享码无效或已过期" }
            val expires = now.plus(7, ChronoUnit.DAYS)
            call.respond(ShareSession(services.shares.issue(redeemed.first, request.actorName.trim(), expires), redeemed.first, request.actorName.trim(), expires.toString()))
        }

        route("/api/v1") {
            get("/species") { call.respond(services.store.listSpecies()) }
        }

        authenticate("access") {
            post("/v1/chat/completions") {
                call.requireOwner(services)
                val request = call.receive<JsonObject>()
                val deviceId = call.request.headers["X-Smart-Pot-Device-Id"]
                    ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{3,64}")) }
                val pot = deviceId?.let { services.store.findPotByDevice(it) }
                call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                    services.ai.proxyOpenAi(request, this, pot)
                }
            }
            route("/api/v1") {
                get("/pots") {
                    val access = call.accessIdentity(services)
                    call.respond(services.pots.listCurrent().filter { access.owner || access.allowedPotId == it.id })
                }
                post("/pots") {
                    call.requireOwner(services)
                    call.respond(HttpStatusCode.Created, services.pots.create(call.receive()))
                }
                route("/pots/{potId}") {
                    get {
                        val pot = call.requirePot(services)
                        call.respond(pot)
                    }
                    patch {
                        val pot = call.requirePot(services, ownerOnly = true)
                        val updated = services.pots.update(pot.id, call.receive())
                        runCatching { services.commands.syncProfile(updated) }
                            .onFailure { System.err.println("Profile sync skipped after pot update: ${it.message}") }
                        call.respond(updated)
                    }
                    get("/snapshot") {
                        val pot = call.requirePot(services)
                        call.respond(services.pots.snapshot(pot.id))
                    }
                    get("/telemetry") {
                        val pot = call.requirePot(services)
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 10_080) ?: 1_440
                        call.respond(services.store.telemetryHistory(pot.id, limit))
                    }
                    get("/alerts") { call.respond(services.store.listAlerts(call.requirePot(services).id)) }
                    get("/care") { call.respond(services.store.listCareLogs(call.requirePot(services).id)) }
                    post("/care") {
                        val pot = call.requirePot(services)
                        call.respond(HttpStatusCode.Created, services.care.add(pot, call.receive(), call.accessIdentity(services).actorName))
                    }
                    get("/reminders") { call.respond(services.store.listReminders(call.requirePot(services).id)) }
                    get("/memories") { call.respond(services.store.listMemories(call.requirePot(services).id)) }
                    post("/memories") {
                        val pot = call.requirePot(services)
                        val request = call.receive<CreateMemoryRequest>()
                        require(request.content.isNotBlank() && request.content.length <= 500) { "记忆内容应为 1-500 个字符" }
                        val memory = UserMemory(UUID.randomUUID().toString(), pot.id, request.content.trim(), Instant.now().toString())
                        services.store.saveMemory(memory)
                        call.respond(HttpStatusCode.Created, memory)
                    }
                    delete("/memories/{memoryId}") {
                        val pot = call.requirePot(services)
                        val memoryId = requireNotNull(call.parameters["memoryId"]) { "缺少记忆 ID" }
                        if (services.store.deleteMemory(pot.id, memoryId)) {
                            call.respond(HttpStatusCode.NoContent)
                        } else {
                            call.respond(HttpStatusCode.NotFound)
                        }
                    }
                    get("/chat/days") {
                        val pot = call.requirePot(services)
                        call.respond(services.store.listMessageDays(pot.id, pot.timezone, 365))
                    }
                    get("/chat") {
                        val pot = call.requirePot(services)
                        val date = call.request.queryParameters["date"]
                        if (date == null) {
                            call.respond(services.store.listMessages(pot.id, 100))
                        } else {
                            require(runCatching { LocalDate.parse(date) }.isSuccess) { "聊天日期格式应为 YYYY-MM-DD" }
                            call.respond(services.store.listMessagesForDay(pot.id, date, pot.timezone, 2_000))
                        }
                    }
                    post("/chat") {
                        val pot = call.requirePot(services)
                        val request = call.receive<ChatRequest>()
                        call.respond(services.ai.chat(pot, request.text, request.source))
                    }
                    get("/diaries") { call.respond(services.store.listDiaries(call.requirePot(services).id)) }
                    post("/diaries") {
                        val pot = call.requirePot(services)
                        val date = LocalDate.now(zoneIdOf(pot.timezone))
                        call.respond(HttpStatusCode.Created, services.diary.saveManual(pot.id, call.receive(), date))
                    }
                    post("/diaries/generate") {
                        val pot = call.requirePot(services)
                        call.respond(services.diary.generate(pot.id))
                    }
                    get("/care-overview") {
                        val pot = call.requirePot(services)
                        val zone = zoneIdOf(pot.timezone)
                        val today = LocalDate.now(zone)
                        val telemetry = services.store.telemetryHistory(pot.id, 1_440)
                            .filter { it.recordedAt.toLocalDate(zone) == today }
                        val focus = focusSummaries(services.store, pot, days = 1, today = today).first()
                        val deviceWeather = weatherFor(today, telemetry, pot)
                        val latitude = call.request.queryParameters["latitude"]?.toDoubleOrNull()
                        val longitude = call.request.queryParameters["longitude"]?.toDoubleOrNull()
                        val weather = if (latitude != null && longitude != null) {
                            services.weather.current(today, latitude, longitude, deviceWeather)
                        } else {
                            deviceWeather
                        }
                        call.respond(CareDayOverview(today.toString(), weather, focus))
                    }
                    get("/focus/daily") {
                        val pot = call.requirePot(services)
                        val days = call.request.queryParameters["days"]?.toIntOrNull()?.coerceIn(1, 30) ?: 5
                        call.respond(focusSummaries(services.store, pot, days))
                    }
                    post("/focus/sessions") {
                        val pot = call.requirePot(services)
                        val request = call.receive<CreateFocusSessionRequest>()
                        val session = focusSessionFrom(pot, request)
                        services.store.saveFocusSession(session)
                        call.respond(HttpStatusCode.Created, session)
                    }
                    get("/schedule") {
                        val pot = call.requirePot(services)
                        call.respond(scheduleState(services.store.listScheduleItems(pot.id)))
                    }
                    post("/schedule") {
                        val pot = call.requirePot(services)
                        require(visibleScheduleItems(services.store.listScheduleItems(pot.id)).count { !it.completed } < 4) {
                            "ESP 日程表最多同时显示 4 条未完成日程"
                        }
                        val item = scheduleItemFrom(pot, call.receive<CreateScheduleItemRequest>(), "APP")
                        services.store.saveScheduleItem(item)
                        syncScheduleToDevice(services.store, services.commands, pot)
                        services.realtime.publish(RealtimeEvent(RealtimeEventType.SCHEDULE, pot.id, appJson.encodeToJsonElement(scheduleState(services.store.listScheduleItems(pot.id)))))
                        call.respond(HttpStatusCode.Created, item)
                    }
                    patch("/schedule/{scheduleId}") {
                        val pot = call.requirePot(services)
                        val scheduleId = requireNotNull(call.parameters["scheduleId"]) { "缺少日程 ID" }
                        val current = requireNotNull(services.store.listScheduleItems(pot.id).firstOrNull { it.id == scheduleId }) { "日程不存在" }
                        val item = updatedScheduleItem(current, call.receive<UpdateScheduleItemRequest>())
                        services.store.saveScheduleItem(item)
                        syncScheduleToDevice(services.store, services.commands, pot)
                        services.realtime.publish(RealtimeEvent(RealtimeEventType.SCHEDULE, pot.id, appJson.encodeToJsonElement(scheduleState(services.store.listScheduleItems(pot.id)))))
                        call.respond(item)
                    }
                    post("/control") {
                        val pot = call.requirePot(services)
                        call.respond(HttpStatusCode.Accepted, services.commands.submit(pot, call.receive()))
                    }
                    post("/share") {
                        val pot = call.requirePot(services, ownerOnly = true)
                        val request = call.receive<CreateShareRequest>()
                        val expires = Instant.now().plus(request.validMinutes.coerceIn(5, 1_440).toLong(), ChronoUnit.MINUTES)
                        val code = ShareCode((100000..999999).random().toString(), expires.toString())
                        services.store.saveShareCode(code, pot.id)
                        call.respond(code)
                    }
                    webSocket("/realtime") {
                        val pot = call.requirePot(services)
                        send(Frame.Text(appJson.encodeToString(RealtimeEvent(RealtimeEventType.SNAPSHOT, pot.id, appJson.encodeToJsonElement(services.pots.snapshot(pot.id))))))
                        services.realtime.stream(pot.id).collect { send(Frame.Text(appJson.encodeToString(it))) }
                    }
                }
                post("/device/{deviceId}/chat") {
                    call.requireOwner(services)
                    val deviceId = requireNotNull(call.parameters["deviceId"])
                    val pot = services.pots.ensureForDevice(deviceId)
                    val request = call.receive<ChatRequest>()
                    call.respond(services.ai.chat(pot, request.text, "DEVICE"))
                }
            }
        }
    }
}

private fun ApplicationCall.accessIdentity(services: ServerServices): AccessIdentity {
    val token = firstBearerToken()
    return if (token == services.config.demoToken) AccessIdentity("主人", owner = true)
    else requireNotNull(services.shares.verify(token)) { "访问令牌无效或已过期" }
}

private fun ApplicationCall.firstBearerToken(): String =
    request.headers.getAll(HttpHeaders.Authorization)
        ?.asSequence()
        ?.flatMap { it.split(',').asSequence() }
        ?.map(String::trim)
        ?.mapNotNull { value ->
            val parts = value.split(Regex("\\s+"), limit = 2)
            val token = parts.getOrNull(1)?.trim().orEmpty()
            token.takeIf { parts.firstOrNull().equals("Bearer", ignoreCase = true) && it.isNotBlank() }
        }
        ?.firstOrNull()
        .orEmpty()

private fun ApplicationCall.requireOwner(services: ServerServices) {
    require(accessIdentity(services).owner) { "仅主人可以执行此操作" }
}

private suspend fun ApplicationCall.requirePot(services: ServerServices, ownerOnly: Boolean = false): PotProfile {
    val access = accessIdentity(services)
    if (ownerOnly) require(access.owner) { "仅主人可以执行此操作" }
    val id = requireNotNull(parameters["potId"]) { "缺少盆栽 ID" }
    require(access.owner || access.allowedPotId == id) { "无权访问该盆栽" }
    return requireNotNull(services.pots.findCurrent(id)) { "盆栽不存在" }
}
