package com.fu1fan.smartpot.server.store

import com.fu1fan.smartpot.protocol.*

data class StoredDeviceState(
    val reported: DeviceReportedState? = null,
    val desired: DeviceDesiredState? = null,
    val online: Boolean = false,
    val lastSeenAt: String? = null,
)

interface SmartPotStore : AutoCloseable {
    suspend fun seedSpecies(species: List<PlantSpecies>)
    suspend fun listSpecies(): List<PlantSpecies>
    suspend fun findSpecies(id: String): PlantSpecies?

    suspend fun listPots(): List<PotProfile>
    suspend fun findPot(id: String): PotProfile?
    suspend fun findPotByDevice(deviceId: String): PotProfile?
    suspend fun savePot(pot: PotProfile): PotProfile

    suspend fun saveTelemetry(potId: String, telemetry: DeviceTelemetry)
    suspend fun latestTelemetry(potId: String): DeviceTelemetry?
    suspend fun telemetryHistory(potId: String, limit: Int = 1_440): List<DeviceTelemetry>
    suspend fun pruneTelemetryBefore(cutoff: String)

    suspend fun saveReportedState(state: DeviceReportedState)
    suspend fun saveDesiredState(state: DeviceDesiredState)
    suspend fun setOnline(deviceId: String, online: Boolean, changedAt: String)
    suspend fun deviceState(deviceId: String): StoredDeviceState

    suspend fun listAlerts(potId: String, activeOnly: Boolean = false): List<PlantAlert>
    suspend fun saveAlert(alert: PlantAlert)

    suspend fun listCareLogs(potId: String): List<CareLog>
    suspend fun saveCareLog(log: CareLog)
    suspend fun listReminders(potId: String): List<CareReminder>
    suspend fun saveReminder(reminder: CareReminder)

    suspend fun listMemories(potId: String): List<UserMemory>
    suspend fun saveMemory(memory: UserMemory)
    suspend fun deleteMemory(potId: String, memoryId: String): Boolean
    suspend fun listMessages(potId: String, limit: Int = 40): List<ChatMessage>
    suspend fun listMessagesForDay(potId: String, date: String, timezone: String, limit: Int = 500): List<ChatMessage>
    suspend fun listMessageDays(potId: String, timezone: String, limit: Int = 90): List<ChatDaySummary>
    suspend fun saveMessage(message: ChatMessage)

    suspend fun affinity(potId: String): AffinityState
    suspend fun saveAffinity(potId: String, affinity: AffinityState)
    suspend fun addAffinityEvent(potId: String, eventKey: String, points: Int, occurredAt: String): Boolean
    suspend fun removeAffinityEvent(potId: String, eventKey: String): Int?
    suspend fun countAffinityEvents(potId: String, eventKeyPrefix: String, since: String): Int
    suspend fun sumAffinityEventPoints(potId: String, eventKeyPrefix: String?, since: String, positiveOnly: Boolean? = null): Int

    suspend fun listDiaries(potId: String): List<PlantDiary>
    suspend fun saveDiary(diary: PlantDiary): Boolean
    suspend fun upsertDiary(diary: PlantDiary)

    suspend fun listFocusSessions(potId: String, since: String? = null): List<FocusSession>
    suspend fun saveFocusSession(session: FocusSession)
    suspend fun deleteFocusSession(potId: String, sessionId: String): Boolean

    suspend fun listScheduleItems(potId: String): List<ScheduleItem>
    suspend fun saveScheduleItem(item: ScheduleItem)

    suspend fun saveShareCode(code: ShareCode, potId: String)
    suspend fun redeemShareCode(code: String, actorName: String, now: String): Pair<String, ShareCode>?

    override fun close() = Unit
}
