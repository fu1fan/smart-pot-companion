package com.fu1fan.smartpot.server.service

import com.fu1fan.smartpot.protocol.*
import com.fu1fan.smartpot.server.AppConfig
import com.fu1fan.smartpot.server.appJson
import com.fu1fan.smartpot.server.focusSessionFrom
import com.fu1fan.smartpot.server.mergeDeviceScheduleItems
import com.fu1fan.smartpot.server.potGrowthDays
import com.fu1fan.smartpot.server.scheduleItemFrom
import com.fu1fan.smartpot.server.scheduleRevision
import com.fu1fan.smartpot.server.scheduleState
import com.fu1fan.smartpot.server.syncProfile
import com.fu1fan.smartpot.server.syncSchedule
import com.fu1fan.smartpot.server.visibleScheduleItems
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class MqttGateway(
    private val config: AppConfig,
    private val scope: CoroutineScope,
    private val store: SmartPotStore,
    private val potService: PotService,
    private val alertService: AlertService,
    private val affinityService: AffinityService,
    private val realtime: RealtimeHub,
    private val conversationMemories: ConversationMemoryService,
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
                val recordedAt = runCatching { Instant.parse(telemetry.recordedAt) }.getOrDefault(Instant.now())
                val zone = runCatching { ZoneId.of(pot.timezone) }.getOrDefault(ZoneId.of("Asia/Shanghai"))
                val localDate = recordedAt.atZone(zone).toLocalDate()
                val dayStart = localDate.atStartOfDay(zone).toInstant().toString()
                val interactions = store.countAffinityEvents(pot.id, "device-event:", dayStart) +
                    store.countAffinityEvents(pot.id, "chat:", dayStart)
                val health = PlantRules.healthPercent(telemetry, pot.species.thresholds, interactions)
                val healthPoints = when (health) {
                    in 85..100 -> 4
                    in 70..84 -> 2
                    in 50..69 -> 0
                    in 30..49 -> -2
                    else -> -5
                }
                affinityService.award(pot.id, "health:$localDate", healthPoints, recordedAt)
                realtime.publish(RealtimeEvent(RealtimeEventType.TELEMETRY, pot.id, appJson.encodeToJsonElement(telemetry)))
            }
            "reported" -> {
                val reported = appJson.decodeFromString<DeviceReportedState>(payload)
                require(reported.deviceId == deviceId)
                store.saveReportedState(reported)
                resyncProfileIfNeeded(pot, reported)
                val scheduleChanged = mergeDeviceScheduleItems(
                    store,
                    pot,
                    reported.scheduleItems,
                    runCatching { Instant.parse(reported.reportedAt) }.getOrDefault(Instant.now()),
                )
                if (scheduleChanged) publishScheduleUpdate(pot)
                else resyncScheduleIfNeeded(pot, reported.scheduleRevision, "reported")
                potService.publishSnapshot(pot.id)
            }
            "acks" -> commandService?.acceptAck(pot.id, appJson.decodeFromString(payload))
            "events" -> {
                val event = appJson.decodeFromString<DeviceEvent>(payload)
                require(event.deviceId == deviceId)
                if (event.type in setOf(DeviceEventType.PHYSICAL_TOUCH, DeviceEventType.REMOTE_TOUCH)) {
                    affinityService.award(pot.id, "device-event:${event.eventId}", 1, Instant.parse(event.occurredAt))
                }
                if (event.isPomodoroCompleted()) {
                    val session = focusSessionFrom(
                        pot,
                        CreateFocusSessionRequest(
                            completedAt = event.occurredAt,
                            minutes = event.focusMinutes(),
                            source = "ESP",
                        ),
                    )
                    store.saveFocusSession(session)
                    affinityService.award(pot.id, "focus:${session.id}", 1, Instant.parse(session.completedAt))
                    realtime.publish(RealtimeEvent(RealtimeEventType.FOCUS, pot.id, appJson.encodeToJsonElement(session)))
                }
                handleScheduleEvent(pot, event)
                if (event.type == DeviceEventType.CONVERSATION) {
                    handleConversationEvent(pot, event)
                }
            }
            "online" -> {
                val online = appJson.decodeFromString<DeviceOnlineState>(payload)
                require(online.deviceId == deviceId)
                store.setOnline(deviceId, online.online, online.changedAt)
                if (online.online) {
                    runCatching { commandService?.syncProfile(pot) }
                        .onFailure { System.err.println("Profile MQTT resync on online skipped: ${it.message}") }
                    resyncScheduleIfNeeded(pot, null, "online")
                }
                realtime.publish(RealtimeEvent(RealtimeEventType.ONLINE, pot.id, appJson.encodeToJsonElement(online)))
            }
        }
    }

    private suspend fun resyncProfileIfNeeded(pot: PotProfile, reported: DeviceReportedState) {
        if (reportedProfileMatches(pot, reported)) return
        runCatching { commandService?.syncProfile(pot) }
            .onFailure { System.err.println("Profile MQTT resync skipped: ${it.message}") }
    }

    private suspend fun resyncScheduleIfNeeded(pot: PotProfile, deviceRevision: Long?, reason: String) {
        val items = store.listScheduleItems(pot.id)
        val serverRevision = scheduleRevision(visibleScheduleItems(items))
        if (deviceRevision != null && deviceRevision == serverRevision) return
        runCatching { commandService?.syncSchedule(pot, items) }
            .onFailure { System.err.println("Schedule MQTT resync on $reason skipped: ${it.message}") }
    }

    private suspend fun handleScheduleEvent(pot: PotProfile, event: DeviceEvent) {
        when (event.type) {
            DeviceEventType.SCHEDULE_ADDED -> {
                val title = event.data.stringValue("title").trim()
                if (title.isBlank()) return
                val displayTime = event.data.stringValue("displayTime")
                    .ifBlank { event.data.stringValue("deadline") }
                val dueAt = event.data.stringValue("dueAt").takeIf { it.isNotBlank() }
                    ?: event.data.epochInstantValue("dueAtEpochSeconds")
                val existing = store.listScheduleItems(pot.id).firstOrNull {
                    !it.completed && it.title == title && it.displayTime == displayTime
                }
                val item = existing?.copy(
                    dueAt = dueAt ?: existing.dueAt,
                    displayTime = displayTime.ifBlank { existing.displayTime },
                    source = "ESP",
                    updatedAt = event.occurredAt,
                ) ?: scheduleItemFrom(
                    pot,
                    CreateScheduleItemRequest(
                        title = title,
                        dueAt = dueAt,
                        displayTime = displayTime,
                    ),
                    source = "ESP",
                    now = Instant.parse(event.occurredAt),
                )
                store.saveScheduleItem(item)
                publishScheduleUpdate(pot)
            }
            DeviceEventType.SCHEDULE_COMPLETED -> {
                if (event.data["kind"]?.jsonPrimitive?.contentOrNull == "pomodoro") return
                val scheduleId = event.data.stringValue("scheduleId").ifBlank { event.data.stringValue("id") }
                val title = event.data.stringValue("title")
                val items = store.listScheduleItems(pot.id)
                val completed = event.data["completed"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
                val current = items.firstOrNull { it.id == scheduleId }
                    ?: items.firstOrNull { it.completed != completed && it.title == title }
                    ?: return
                store.saveScheduleItem(
                    current.copy(
                        completed = completed,
                        completedAt = if (completed) current.completedAt ?: event.occurredAt else null,
                        updatedAt = event.occurredAt,
                    ),
                )
                if (completed) affinityService.award(pot.id, "schedule:${current.id}", 1, Instant.parse(event.occurredAt))
                else affinityService.revoke(pot.id, "schedule:${current.id}", Instant.parse(event.occurredAt))
                publishScheduleUpdate(pot)
            }
            else -> Unit
        }
    }

    private suspend fun publishScheduleUpdate(pot: PotProfile) {
        val items = store.listScheduleItems(pot.id)
        realtime.publish(RealtimeEvent(RealtimeEventType.SCHEDULE, pot.id, appJson.encodeToJsonElement(scheduleState(items))))
        runCatching { commandService?.syncSchedule(pot, items) }
            .onFailure { System.err.println("Schedule MQTT resync skipped: ${it.message}") }
    }

    private suspend fun handleConversationEvent(pot: PotProfile, event: DeviceEvent) {
        val response = conversationMessagesFromEvent(pot, event) ?: return
        store.saveMessage(response.userMessage)
        store.saveMessage(response.assistantMessage)
        affinityService.award(pot.id, "chat:${response.userMessage.id}", 1, Instant.parse(response.userMessage.createdAt))
        realtime.publish(RealtimeEvent(RealtimeEventType.CHAT, pot.id, appJson.encodeToJsonElement(response)))
        conversationMemories.enqueue(pot, response.userMessage)
    }

    override fun close() {
        client?.disconnect()?.join()
    }
}

internal fun reportedProfileMatches(pot: PotProfile, reported: DeviceReportedState): Boolean {
    val actual = reported.thresholds ?: return false
    val expected = pot.species.thresholds
    return actual.soilMinPercent == expected.soilMinPercent &&
        actual.soilMaxPercent == expected.soilMaxPercent &&
        actual.lightMinLux == expected.lightMinLux &&
        actual.lightMaxLux == expected.lightMaxLux &&
        reported.growthDays == potGrowthDays(pot)
}

internal fun conversationMessagesFromEvent(pot: PotProfile, event: DeviceEvent): ChatResponse? {
    if (event.type != DeviceEventType.CONVERSATION) return null
    val userText = event.data.stringValue("userText").trim().take(1_000)
    val assistantText = event.data.stringValue("assistantText").trim().take(4_000)
    if (userText.isBlank() || assistantText.isBlank()) return null
    val occurredAt = runCatching { Instant.parse(event.occurredAt) }.getOrElse { Instant.now() }
    fun messageId(role: String): String = UUID.nameUUIDFromBytes(
        "${pot.id}:${event.eventId}:$role".toByteArray(StandardCharsets.UTF_8),
    ).toString()
    return ChatResponse(
        userMessage = ChatMessage(
            id = messageId("user"),
            potId = pot.id,
            role = ChatRole.USER,
            content = userText,
            createdAt = occurredAt.toString(),
            source = "ESP",
        ),
        assistantMessage = ChatMessage(
            id = messageId("assistant"),
            potId = pot.id,
            role = ChatRole.ASSISTANT,
            content = assistantText,
            createdAt = occurredAt.plusNanos(1_000_000).toString(),
            source = "ESP",
        ),
    )
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

private fun DeviceEvent.isPomodoroCompleted(): Boolean =
    type == DeviceEventType.POMODORO_COMPLETED ||
        (type == DeviceEventType.SCHEDULE_COMPLETED && data["kind"]?.jsonPrimitive?.contentOrNull == "pomodoro")

private fun DeviceEvent.focusMinutes(): Int =
    data["minutes"]?.jsonPrimitive?.intOrNull
        ?: data["durationMinutes"]?.jsonPrimitive?.intOrNull
        ?: 25

private fun JsonObject.stringValue(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject.epochInstantValue(key: String): String? =
    this[key]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).toString() }
