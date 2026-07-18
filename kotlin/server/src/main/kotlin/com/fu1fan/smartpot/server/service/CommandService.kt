package com.fu1fan.smartpot.server.service

import com.fu1fan.smartpot.protocol.*
import com.fu1fan.smartpot.server.appJson
import com.fu1fan.smartpot.server.store.SmartPotStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CommandService(
    private val store: SmartPotStore,
    private val mqtt: MqttGateway,
    private val realtime: RealtimeHub,
) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<DeviceCommandAck>>()

    suspend fun submit(pot: PotProfile, request: DeviceControlRequest): CommandSubmission {
        validate(request)
        val now = Instant.now()
        val command = DeviceCommand(
            commandId = UUID.randomUUID().toString(),
            deviceId = pot.deviceId,
            type = request.type,
            issuedAt = now.toString(),
            expiresAt = now.plus(30, ChronoUnit.SECONDS).toString(),
            payload = buildJsonObject {
                request.brightnessPercent?.let { put("brightnessPercent", it) }
                request.volumePercent?.let { put("volumePercent", it) }
                request.standby?.let { put("standby", it) }
                request.lightStripManualMode?.let { put("manualMode", it) }
                request.lightStripOn?.let { put("on", it) }
                request.lightStripOffPeriodEnabled?.let { put("offPeriodEnabled", it) }
                request.lightStripOffStartMinute?.let { put("offStartMinute", it) }
                request.lightStripOffEndMinute?.let { put("offEndMinute", it) }
                request.text?.trim()?.let { put("text", it) }
                request.emojiId?.let {
                    put("mode", "emoji")
                    put("emojiId", it)
                }
                (request.durationSeconds ?: if (request.emojiId != null) 2 else null)?.let {
                    put("durationSeconds", it)
                    put("durationMs", it * 1000)
                }
                if (request.type == DeviceCommandType.SYNC_PROFILE) {
                    put("thresholds", appJson.encodeToJsonElement(pot.species.thresholds))
                }
                if (request.type == DeviceCommandType.SYNC_SCHEDULE) {
                    request.scheduleRevision?.let { put("revision", it) }
                    put("items", appJson.encodeToJsonElement(request.scheduleItems))
                }
            },
        )
        val waiter = CompletableDeferred<DeviceCommandAck>()
        pending[command.commandId] = waiter
        mqtt.publishCommand(command)
        val ack = withTimeoutOrNull(5_000) { waiter.await() }
        pending.remove(command.commandId)
        return CommandSubmission(command, ack != null, ack)
    }

    suspend fun syncDesired(pot: PotProfile, desired: DeviceDesiredState) {
        require(desired.deviceId == pot.deviceId) { "设备 ID 不匹配" }
        store.saveDesiredState(desired)
        mqtt.publishDesired(desired)
    }

    fun acceptAck(potId: String, ack: DeviceCommandAck) {
        pending[ack.commandId]?.complete(ack)
        realtime.publish(RealtimeEvent(RealtimeEventType.COMMAND_ACK, potId, appJson.encodeToJsonElement(ack)))
    }

    private fun validate(request: DeviceControlRequest) {
        request.brightnessPercent?.let { require(it in 0..100) { "屏幕亮度必须为 0-100" } }
        request.volumePercent?.let { require(it in 0..100) { "音量必须为 0-100" } }
        request.lightStripOffStartMinute?.let { require(it in 0..1_439) { "灭灯开始时间必须在一天范围内" } }
        request.lightStripOffEndMinute?.let { require(it in 0..1_439) { "灭灯结束时间必须在一天范围内" } }
        request.durationSeconds?.let { require(it in 1..300) { "显示时长必须为 1-300 秒" } }
        request.text?.let {
            require(it.length <= 96) { "屏幕文字不能超过 96 个字符" }
            require(it.toByteArray(Charsets.UTF_8).size <= 384) { "屏幕文字过长" }
        }
        request.emojiId?.let { require(it in EMOJI_IDS) { "不支持的表情" } }
        when (request.type) {
            DeviceCommandType.SET_BRIGHTNESS -> requireNotNull(request.brightnessPercent)
            DeviceCommandType.SET_VOLUME -> requireNotNull(request.volumePercent)
            DeviceCommandType.SET_STANDBY -> requireNotNull(request.standby)
            DeviceCommandType.SHOW_CONTENT -> require(!request.text.isNullOrBlank() || request.emojiId != null)
            DeviceCommandType.SYNC_SCHEDULE -> require(request.scheduleItems.size <= 8) { "同步日程不能超过 8 条" }
            DeviceCommandType.SET_LIGHT_STRIP_CONTROL -> {
                require(
                    request.lightStripManualMode != null ||
                        request.lightStripOn != null ||
                        request.lightStripOffPeriodEnabled != null ||
                        request.lightStripOffStartMinute != null ||
                        request.lightStripOffEndMinute != null,
                ) { "灯带控制命令缺少参数" }
                if (request.lightStripOffPeriodEnabled == true) {
                    requireNotNull(request.lightStripOffStartMinute) { "开启灭灯时间段时需要开始时间" }
                    requireNotNull(request.lightStripOffEndMinute) { "开启灭灯时间段时需要结束时间" }
                    require(request.lightStripOffStartMinute != request.lightStripOffEndMinute) { "灭灯开始和结束时间不能相同" }
                }
            }
            else -> Unit
        }
    }

    companion object {
        val EMOJI_IDS = setOf("heart", "smile", "happy", "thirsty", "dark", "weak", "wave", "star", "flower", "water", "sun", "sleep")
    }
}
