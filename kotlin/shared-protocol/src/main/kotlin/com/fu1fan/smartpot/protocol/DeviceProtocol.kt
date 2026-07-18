package com.fu1fan.smartpot.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

const val PROTOCOL_VERSION = 1

@Serializable
enum class PlantMood { HAPPY, THIRSTY, DARK, WEAK }

@Serializable
data class MotionState(
    val rollDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    val moving: Boolean = false,
    val tiltLevel: Int = 0,
)

@Serializable
data class DeviceTelemetry(
    val schemaVersion: Int = PROTOCOL_VERSION,
    val deviceId: String,
    val sequence: Long,
    val recordedAt: String,
    val soilPercent: Int,
    val soilAdcRaw: Long = 0,
    val soilDigitalDry: Boolean = false,
    val lightLux: Long,
    val lightPercent: Int,
    val touchCount: Long,
    val touchActive: Boolean = false,
    val motion: MotionState? = null,
    val mood: PlantMood,
    val wifiRssi: Int? = null,
    val uptimeSeconds: Long,
)

@Serializable
data class DisplayContent(
    val text: String = "",
    val emojiId: String? = null,
    val expiresAt: String? = null,
)

@Serializable
data class PlantThresholds(
    val soilMinPercent: Int,
    val soilMaxPercent: Int,
    val lightMinLux: Int,
    val lightMaxLux: Int,
    val temperatureMinC: Double? = null,
    val temperatureMaxC: Double? = null,
)

@Serializable
data class DeviceReportedState(
    val schemaVersion: Int = PROTOCOL_VERSION,
    val deviceId: String,
    val reportedAt: String,
    val brightnessPercent: Int,
    val volumePercent: Int,
    val standby: Boolean,
    val content: DisplayContent = DisplayContent(),
    val thresholds: PlantThresholds? = null,
    val scheduleRevision: Long = 0,
    val firmwareVersion: String,
)

@Serializable
data class DeviceDesiredState(
    val schemaVersion: Int = PROTOCOL_VERSION,
    val deviceId: String,
    val revision: Long,
    val updatedAt: String,
    val brightnessPercent: Int? = null,
    val volumePercent: Int? = null,
    val standby: Boolean? = null,
    val thresholds: PlantThresholds? = null,
)

@Serializable
enum class DeviceCommandType {
    SET_BRIGHTNESS,
    SET_VOLUME,
    SET_STANDBY,
    SHOW_CONTENT,
    REMOTE_TOUCH,
    RESTART,
    SYNC_PROFILE,
    SYNC_SCHEDULE,
    SPEAK_TEXT,
}

@Serializable
data class DeviceCommand(
    val schemaVersion: Int = PROTOCOL_VERSION,
    val commandId: String,
    val deviceId: String,
    val type: DeviceCommandType,
    val issuedAt: String,
    val expiresAt: String,
    val payload: JsonObject = buildJsonObject { },
)

@Serializable
enum class CommandAckStatus { ACCEPTED, COMPLETED, FAILED, EXPIRED, DUPLICATE }

@Serializable
data class DeviceCommandAck(
    val schemaVersion: Int = PROTOCOL_VERSION,
    val commandId: String,
    val deviceId: String,
    val status: CommandAckStatus,
    val acknowledgedAt: String,
    val message: String? = null,
    val reportedState: DeviceReportedState? = null,
)

@Serializable
enum class DeviceEventType {
    PHYSICAL_TOUCH,
    REMOTE_TOUCH,
    MOVE_STARTED,
    MOVE_STOPPED,
    TILT_LIGHT,
    TILT_SEVERE,
    TILT_RECOVERED,
    SCHEDULE_COMPLETED,
    POMODORO_COMPLETED,
    CONVERSATION,
}

@Serializable
data class DeviceEvent(
    val schemaVersion: Int = PROTOCOL_VERSION,
    val eventId: String,
    val deviceId: String,
    val type: DeviceEventType,
    val occurredAt: String,
    val data: JsonObject = buildJsonObject { },
)

@Serializable
data class DeviceOnlineState(
    val deviceId: String,
    val online: Boolean,
    val changedAt: String,
)
