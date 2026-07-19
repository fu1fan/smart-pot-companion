package com.fu1fan.smartpot.protocol

import kotlinx.serialization.Serializable

@Serializable
data class PlantSpecies(
    val id: String,
    val chineseName: String,
    val scientificName: String,
    val aliases: List<String> = emptyList(),
    val thresholds: PlantThresholds,
    val wateringIntervalDays: Int,
    val fertilizingIntervalDays: Int,
    val pruningIntervalDays: Int? = null,
    val repottingIntervalDays: Int? = null,
    val knowledge: String,
)

@Serializable
data class PotProfile(
    val id: String,
    val deviceId: String,
    val displayName: String,
    val species: PlantSpecies,
    val timezone: String = "Asia/Shanghai",
    val createdAt: String,
)

@Serializable
enum class SoilStatus { TOO_DRY, SUITABLE, TOO_WET, UNKNOWN }

@Serializable
enum class LightStatus { DARK, DIFFUSE, TOO_STRONG, UNKNOWN }

@Serializable
data class EvaluatedPlantState(
    val soilStatus: SoilStatus,
    val lightStatus: LightStatus,
    val soilAdvice: String,
    val lightAdvice: String,
)

@Serializable
data class PotSnapshot(
    val pot: PotProfile,
    val telemetry: DeviceTelemetry? = null,
    val deviceState: DeviceReportedState? = null,
    val online: Boolean = false,
    val lastSeenAt: String? = null,
    val evaluated: EvaluatedPlantState? = null,
    val activeAlerts: List<PlantAlert> = emptyList(),
    val affinity: AffinityState = AffinityState(),
    val dailyTouchCount: Int = 0,
)

@Serializable
enum class AlertType { SOIL_DRY, SOIL_WET, LIGHT_LOW, LIGHT_HIGH, DEVICE_OFFLINE, TILT_SEVERE }

@Serializable
enum class AlertStatus { ACTIVE, RECOVERED }

@Serializable
data class PlantAlert(
    val id: String,
    val potId: String,
    val type: AlertType,
    val status: AlertStatus,
    val message: String,
    val startedAt: String,
    val recoveredAt: String? = null,
)

@Serializable
enum class CareType { WATER, FERTILIZE, PRUNE, REPOT, NEW_LEAF, OTHER }

@Serializable
data class CareLog(
    val id: String,
    val potId: String,
    val type: CareType,
    val occurredAt: String,
    val note: String = "",
    val actorName: String = "主人",
)

@Serializable
data class CreateCareLogRequest(
    val type: CareType,
    val occurredAt: String? = null,
    val note: String = "",
)

@Serializable
enum class ReminderStatus { PENDING, COMPLETED, DISMISSED }

@Serializable
data class CareReminder(
    val id: String,
    val potId: String,
    val type: CareType,
    val title: String,
    val dueAt: String,
    val status: ReminderStatus = ReminderStatus.PENDING,
)

@Serializable
data class UserMemory(
    val id: String,
    val potId: String,
    val content: String,
    val createdAt: String,
)

@Serializable
data class CreateMemoryRequest(val content: String)

@Serializable
enum class ChatRole { USER, ASSISTANT, SYSTEM }

@Serializable
data class ChatMessage(
    val id: String,
    val potId: String,
    val role: ChatRole,
    val content: String,
    val createdAt: String,
    val source: String = "APP",
)

@Serializable
data class ChatRequest(
    val text: String,
    val source: String = "APP",
)

@Serializable
data class ChatResponse(
    val userMessage: ChatMessage,
    val assistantMessage: ChatMessage,
)

@Serializable
data class ChatDaySummary(
    val date: String,
    val messageCount: Int,
)

@Serializable
enum class AffinityLevel { STRANGER, FAMILIAR, CLOSE, TRUSTED, BEST_FRIEND }

@Serializable
data class AffinityState(
    val score: Int = 20,
    val level: AffinityLevel = AffinityLevel.FAMILIAR,
    val updatedAt: String? = null,
)

@Serializable
enum class DiaryAuthor { WHEAT, USER }

@Serializable
data class PlantDiary(
    val id: String,
    val potId: String,
    val diaryDate: String,
    val title: String,
    val content: String,
    val createdAt: String,
    val imageDataUrls: List<String> = emptyList(),
    val moodEmoji: String? = null,
    val author: DiaryAuthor = DiaryAuthor.WHEAT,
)

@Serializable
data class CreateDiaryRequest(
    val title: String,
    val content: String,
    val imageDataUrls: List<String> = emptyList(),
    val moodEmoji: String? = null,
)

@Serializable
data class CareWeather(
    val date: String,
    val condition: String,
    val averageLightLux: Int? = null,
    val maxLightLux: Int? = null,
    val hint: String = "",
    val temperatureC: Double? = null,
    val relativeHumidityPercent: Int? = null,
    val source: String = "DEVICE",
)

@Serializable
data class FocusSession(
    val id: String,
    val potId: String,
    val completedAt: String,
    val minutes: Int = 25,
    val source: String = "APP",
)

@Serializable
data class CreateFocusSessionRequest(
    val completedAt: String? = null,
    val minutes: Int = 25,
    val source: String = "APP",
)

@Serializable
data class DailyFocusSummary(
    val date: String,
    val pomodoroCount: Int,
    val focusMinutes: Int,
    val targetPomodoroCount: Int = 4,
    val scheduleCompletionPercent: Int,
)

@Serializable
data class CareDayOverview(
    val date: String,
    val weather: CareWeather,
    val focus: DailyFocusSummary,
)

@Serializable
data class ScheduleItem(
    val id: String,
    val potId: String,
    val title: String,
    val dueAt: String? = null,
    val displayTime: String = "",
    val completed: Boolean = false,
    val completedAt: String? = null,
    val source: String = "APP",
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class CreateScheduleItemRequest(
    val title: String,
    val dueAt: String? = null,
    val displayTime: String = "",
)

@Serializable
data class UpdateScheduleItemRequest(
    val title: String? = null,
    val dueAt: String? = null,
    val displayTime: String? = null,
    val completed: Boolean? = null,
)

@Serializable
data class ScheduleSyncState(
    val revision: Long,
    val items: List<ScheduleItem>,
)

@Serializable
data class CreatePotRequest(
    val deviceId: String,
    val displayName: String,
    val speciesId: String,
)

@Serializable
data class UpdatePotRequest(
    val displayName: String? = null,
    val speciesId: String? = null,
)

@Serializable
data class DeviceControlRequest(
    val type: DeviceCommandType,
    val brightnessPercent: Int? = null,
    val volumePercent: Int? = null,
    val standby: Boolean? = null,
    val lightStripManualMode: Boolean? = null,
    val lightStripOn: Boolean? = null,
    val lightStripOffPeriodEnabled: Boolean? = null,
    val lightStripOffStartMinute: Int? = null,
    val lightStripOffEndMinute: Int? = null,
    val text: String? = null,
    val emojiId: String? = null,
    val durationSeconds: Int? = null,
    val scheduleRevision: Long? = null,
    val scheduleItems: List<ScheduleItem> = emptyList(),
)

@Serializable
data class CommandSubmission(
    val command: DeviceCommand,
    val acknowledged: Boolean = false,
    val ack: DeviceCommandAck? = null,
)

@Serializable
data class CreateShareRequest(val validMinutes: Int = 30)

@Serializable
data class ShareCode(val code: String, val expiresAt: String)

@Serializable
data class RedeemShareRequest(val code: String, val actorName: String)

@Serializable
data class ShareSession(val token: String, val potId: String, val actorName: String, val expiresAt: String)

@Serializable
enum class RealtimeEventType { SNAPSHOT, TELEMETRY, ONLINE, ALERT, COMMAND_ACK, DIARY, AFFINITY, FOCUS, SCHEDULE, CHAT }

@Serializable
data class RealtimeEvent(
    val type: RealtimeEventType,
    val potId: String,
    val payload: kotlinx.serialization.json.JsonElement,
)

@Serializable
data class ApiError(val code: String, val message: String)
