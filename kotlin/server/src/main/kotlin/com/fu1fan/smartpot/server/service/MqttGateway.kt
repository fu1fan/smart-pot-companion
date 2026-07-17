package com.fu1fan.smartpot.server.service

import com.fu1fan.smartpot.protocol.*
import com.fu1fan.smartpot.server.AppConfig
import com.fu1fan.smartpot.server.appJson
import com.fu1fan.smartpot.server.store.SmartPotStore
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

class MqttGateway(
    private val config: AppConfig,
    private val scope: CoroutineScope,
    private val store: SmartPotStore,
    private val potService: PotService,
    private val alertService: AlertService,
    private val affinityService: AffinityService,
    private val realtime: RealtimeHub,
) : AutoCloseable {
    private var client: Mqtt5AsyncClient? = null
    var commandService: CommandService? = null

    fun start() {
        val builder = MqttClient.builder()
            .useMqttVersion5()
            .identifier("smartpot-server-${UUID.randomUUID().toString().take(8)}")
            .serverHost(config.mqttHost)
            .serverPort(config.mqttPort)
        if (config.mqttTls) builder.sslWithDefaultConfig()
        val mqtt = builder.buildAsync()
        val connect = mqtt.connectWith().cleanStart(false).keepAlive(30)
        if (!config.mqttUsername.isNullOrBlank()) {
            connect.simpleAuth()
                .username(config.mqttUsername)
                .password(StandardCharsets.UTF_8.encode(config.mqttPassword.orEmpty()))
                .applySimpleAuth()
        }
        connect.send().join()
        mqtt.publishes(MqttGlobalPublishFilter.ALL) { publish ->
            val topic = publish.topic.toString()
            val payload = StandardCharsets.UTF_8.decode(publish.payload.orElse(ByteBuffer.allocate(0))).toString()
            scope.launch { runCatching { consume(topic, payload) }.onFailure { System.err.println("MQTT message rejected: $topic: ${it.message}") } }
        }
        listOf("telemetry", "reported", "acks", "events", "online").forEach { suffix ->
            mqtt.subscribeWith().topicFilter("smartpot/v1/devices/+/$suffix").qos(MqttQos.AT_LEAST_ONCE).send().join()
        }
        client = mqtt
    }

    suspend fun publishCommand(command: DeviceCommand) = publish(
        "smartpot/v1/devices/${command.deviceId}/commands",
        appJson.encodeToString(command),
        retain = false,
    )

    suspend fun publishDesired(desired: DeviceDesiredState) = publish(
        "smartpot/v1/devices/${desired.deviceId}/desired",
        appJson.encodeToString(desired),
        retain = true,
    )

    private fun publish(topic: String, body: String, retain: Boolean) {
        requireNotNull(client) { "MQTT 尚未连接" }.publishWith()
            .topic(topic).qos(MqttQos.AT_LEAST_ONCE).retain(retain)
            .payload(StandardCharsets.UTF_8.encode(body)).send().join()
    }

    private suspend fun consume(topic: String, payload: String) {
        val (deviceId, kind) = parseDeviceTopic(topic)
        val pot = potService.ensureForDevice(deviceId)
        when (kind) {
            "telemetry" -> {
                val telemetry = appJson.decodeFromString<DeviceTelemetry>(payload)
                require(telemetry.deviceId == deviceId)
                store.saveTelemetry(pot.id, telemetry)
                store.setOnline(deviceId, true, telemetry.recordedAt)
                alertService.evaluate(pot, telemetry)
                realtime.publish(RealtimeEvent(RealtimeEventType.TELEMETRY, pot.id, appJson.encodeToJsonElement(telemetry)))
            }
            "reported" -> {
                val reported = appJson.decodeFromString<DeviceReportedState>(payload)
                require(reported.deviceId == deviceId)
                store.saveReportedState(reported)
                potService.publishSnapshot(pot.id)
            }
            "acks" -> commandService?.acceptAck(pot.id, appJson.decodeFromString(payload))
            "events" -> {
                val event = appJson.decodeFromString<DeviceEvent>(payload)
                require(event.deviceId == deviceId)
                if (event.type in setOf(DeviceEventType.PHYSICAL_TOUCH, DeviceEventType.REMOTE_TOUCH)) {
                    affinityService.award(pot.id, "device-event:${event.eventId}", 1, Instant.parse(event.occurredAt))
                }
            }
            "online" -> {
                val online = appJson.decodeFromString<DeviceOnlineState>(payload)
                require(online.deviceId == deviceId)
                store.setOnline(deviceId, online.online, online.changedAt)
                realtime.publish(RealtimeEvent(RealtimeEventType.ONLINE, pot.id, appJson.encodeToJsonElement(online)))
            }
        }
    }

    override fun close() {
        client?.disconnect()?.join()
    }
}

internal data class DeviceTopic(val deviceId: String, val kind: String)

internal fun parseDeviceTopic(topic: String): DeviceTopic {
    val parts = topic.split('/')
    require(parts.size == 5) { "invalid device topic: $topic" }
    require(parts[0] == "smartpot" && parts[1] == "v1" && parts[2] == "devices") { "invalid device topic: $topic" }
    require(parts[3].matches(Regex("[A-Za-z0-9_-]{3,64}"))) { "invalid device id in topic: $topic" }
    require(parts[4] in setOf("telemetry", "reported", "acks", "events", "online")) { "invalid device topic kind: $topic" }
    return DeviceTopic(deviceId = parts[3], kind = parts[4])
}
