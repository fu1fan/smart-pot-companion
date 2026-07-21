package com.fu1fan.smartpot.server.store

import com.fu1fan.smartpot.protocol.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

class InMemorySmartPotStore : SmartPotStore {
    private val lock = Mutex()
    private val species = linkedMapOf<String, PlantSpecies>()
    private val pots = linkedMapOf<String, PotProfile>()
    private val telemetry = ConcurrentHashMap<String, MutableList<DeviceTelemetry>>()
    private val states = ConcurrentHashMap<String, StoredDeviceState>()
    private val alerts = ConcurrentHashMap<String, MutableList<PlantAlert>>()
    private val careLogs = ConcurrentHashMap<String, MutableList<CareLog>>()
    private val reminders = ConcurrentHashMap<String, MutableList<CareReminder>>()
    private val memories = ConcurrentHashMap<String, MutableList<UserMemory>>()
    private val messages = ConcurrentHashMap<String, MutableList<ChatMessage>>()
    private val affinities = ConcurrentHashMap<String, AffinityState>()
    private val affinityEvents = ConcurrentHashMap<String, Pair<Int, String>>()
    private val diaries = ConcurrentHashMap<String, MutableList<PlantDiary>>()
    private val focusSessions = ConcurrentHashMap<String, MutableList<FocusSession>>()
    private val scheduleItems = ConcurrentHashMap<String, MutableList<ScheduleItem>>()
    private val shares = ConcurrentHashMap<String, Pair<String, ShareCode>>()

    override suspend fun seedSpecies(species: List<PlantSpecies>) = lock.withLock {
        species.forEach { this.species.putIfAbsent(it.id, it) }
    }

    override suspend fun listSpecies() = lock.withLock { species.values.toList() }
    override suspend fun findSpecies(id: String) = lock.withLock { species[id] }
    override suspend fun listPots() = lock.withLock { pots.values.toList() }
    override suspend fun findPot(id: String) = lock.withLock { pots[id] }
    override suspend fun findPotByDevice(deviceId: String) = lock.withLock { pots.values.firstOrNull { it.deviceId == deviceId } }
    override suspend fun savePot(pot: PotProfile) = lock.withLock { pots[pot.id] = pot; pot }

    override suspend fun saveTelemetry(potId: String, telemetry: DeviceTelemetry) {
        val list = this.telemetry.computeIfAbsent(potId) { mutableListOf() }
        synchronized(list) {
            list += telemetry
            if (list.size > 1_440) list.removeFirst()
        }
    }

    override suspend fun latestTelemetry(potId: String) = telemetry[potId]?.let { synchronized(it) { it.lastOrNull() } }
    override suspend fun telemetryHistory(potId: String, limit: Int) = telemetry[potId]?.let { synchronized(it) { it.takeLast(limit) } } ?: emptyList()
    override suspend fun pruneTelemetryBefore(cutoff: String) {
        val cutoffInstant = Instant.parse(cutoff)
        telemetry.values.forEach { list ->
            synchronized(list) { list.removeAll { Instant.parse(it.recordedAt).isBefore(cutoffInstant) } }
        }
    }

    override suspend fun saveReportedState(state: DeviceReportedState) {
        states.compute(state.deviceId) { _, old -> (old ?: StoredDeviceState()).copy(reported = state, lastSeenAt = state.reportedAt) }
    }

    override suspend fun saveDesiredState(state: DeviceDesiredState) {
        states.compute(state.deviceId) { _, old -> (old ?: StoredDeviceState()).copy(desired = state) }
    }

    override suspend fun setOnline(deviceId: String, online: Boolean, changedAt: String) {
        states.compute(deviceId) { _, old -> (old ?: StoredDeviceState()).copy(online = online, lastSeenAt = changedAt) }
    }

    override suspend fun deviceState(deviceId: String) = states[deviceId] ?: StoredDeviceState()

    override suspend fun listAlerts(potId: String, activeOnly: Boolean): List<PlantAlert> =
        alerts[potId]?.let { synchronized(it) { it.filter { alert -> !activeOnly || alert.status == AlertStatus.ACTIVE } } } ?: emptyList()

    override suspend fun saveAlert(alert: PlantAlert) {
        val list = alerts.computeIfAbsent(alert.potId) { mutableListOf() }
        synchronized(list) { list.removeAll { it.id == alert.id }; list += alert }
    }

    override suspend fun listCareLogs(potId: String) = careLogs[potId]?.let { synchronized(it) { it.sortedByDescending(CareLog::occurredAt) } } ?: emptyList()
    override suspend fun saveCareLog(log: CareLog) { careLogs.computeIfAbsent(log.potId) { mutableListOf() }.add(log) }
    override suspend fun listReminders(potId: String) = reminders[potId]?.let { synchronized(it) { it.sortedBy(CareReminder::dueAt) } } ?: emptyList()
    override suspend fun saveReminder(reminder: CareReminder) {
        val list = reminders.computeIfAbsent(reminder.potId) { mutableListOf() }
        synchronized(list) { list.removeAll { it.id == reminder.id }; list += reminder }
    }

    override suspend fun listMemories(potId: String) = memories[potId]?.let { synchronized(it) { it.toList() } } ?: emptyList()
    override suspend fun saveMemory(memory: UserMemory) {
        val list = memories.computeIfAbsent(memory.potId) { mutableListOf() }
        synchronized(list) {
            if (list.none { it.id == memory.id }) list += memory
        }
    }
    override suspend fun deleteMemory(potId: String, memoryId: String): Boolean = memories[potId]?.let { list ->
        synchronized(list) { list.removeAll { it.id == memoryId } }
    } ?: false
    override suspend fun listMessages(potId: String, limit: Int) = messages[potId]?.let { synchronized(it) { it.takeLast(limit) } } ?: emptyList()
    override suspend fun listMessagesForDay(potId: String, date: String, timezone: String, limit: Int): List<ChatMessage> {
        val day = LocalDate.parse(date)
        val zone = ZoneId.of(timezone)
        return messages[potId]?.let { list ->
            synchronized(list) {
                list.filter { Instant.parse(it.createdAt).atZone(zone).toLocalDate() == day }.takeLast(limit)
            }
        } ?: emptyList()
    }

    override suspend fun listMessageDays(potId: String, timezone: String, limit: Int): List<ChatDaySummary> {
        val zone = ZoneId.of(timezone)
        return messages[potId]?.let { list ->
            synchronized(list) {
                list.groupingBy { Instant.parse(it.createdAt).atZone(zone).toLocalDate().toString() }
                    .eachCount()
                    .entries
                    .sortedByDescending(Map.Entry<String, Int>::key)
                    .take(limit)
                    .map { ChatDaySummary(it.key, it.value) }
            }
        } ?: emptyList()
    }

    override suspend fun saveMessage(message: ChatMessage) {
        val list = messages.computeIfAbsent(message.potId) { mutableListOf() }
        synchronized(list) {
            if (list.none { it.id == message.id }) list += message
        }
    }

    override suspend fun affinity(potId: String) = affinities[potId] ?: PlantRules.initialAffinityState()
    override suspend fun saveAffinity(potId: String, affinity: AffinityState) { affinities[potId] = affinity }
    override suspend fun addAffinityEvent(potId: String, eventKey: String, points: Int, occurredAt: String): Boolean =
        affinityEvents.putIfAbsent("$potId:$eventKey", points to occurredAt) == null

    override suspend fun removeAffinityEvent(potId: String, eventKey: String): Int? =
        affinityEvents.remove("$potId:$eventKey")?.first

    override suspend fun countAffinityEvents(potId: String, eventKeyPrefix: String, since: String): Int {
        val prefix = "$potId:$eventKeyPrefix"
        val cutoff = Instant.parse(since)
        return affinityEvents.count { (key, event) ->
            key.startsWith(prefix) && !Instant.parse(event.second).isBefore(cutoff)
        }
    }

    override suspend fun sumAffinityEventPoints(potId: String, eventKeyPrefix: String?, since: String, positiveOnly: Boolean?): Int {
        val prefix = eventKeyPrefix?.let { "$potId:$it" } ?: "$potId:"
        val cutoff = Instant.parse(since)
        return affinityEvents.entries.sumOf { (key, event) ->
            val signMatches = positiveOnly == null || if (positiveOnly) event.first > 0 else event.first < 0
            if (key.startsWith(prefix) && signMatches && !Instant.parse(event.second).isBefore(cutoff)) event.first else 0
        }
    }

    override suspend fun listDiaries(potId: String) = diaries[potId]?.let { synchronized(it) { it.sortedByDescending(PlantDiary::diaryDate) } } ?: emptyList()
    override suspend fun saveDiary(diary: PlantDiary): Boolean {
        val list = diaries.computeIfAbsent(diary.potId) { mutableListOf() }
        synchronized(list) {
            if (list.any { it.diaryDate == diary.diaryDate && it.author == diary.author }) return false
            list += diary
        }
        return true
    }
    override suspend fun upsertDiary(diary: PlantDiary) {
        val list = diaries.computeIfAbsent(diary.potId) { mutableListOf() }
        synchronized(list) {
            list.removeAll { it.diaryDate == diary.diaryDate && it.author == diary.author }
            list += diary
        }
    }
    override suspend fun deleteUserDiary(potId: String, diaryId: String): Boolean = diaries[potId]?.let { list ->
        synchronized(list) { list.removeAll { it.id == diaryId && it.author == DiaryAuthor.USER } }
    } ?: false

    override suspend fun listFocusSessions(potId: String, since: String?) =
        focusSessions[potId]?.let { list ->
            synchronized(list) {
                list.filter { since == null || it.completedAt >= since }.sortedBy(FocusSession::completedAt)
            }
        } ?: emptyList()

    override suspend fun saveFocusSession(session: FocusSession) {
        val list = focusSessions.computeIfAbsent(session.potId) { mutableListOf() }
        synchronized(list) { list.removeAll { it.id == session.id }; list += session }
    }

    override suspend fun deleteFocusSession(potId: String, sessionId: String): Boolean {
        val list = focusSessions[potId] ?: return false
        return synchronized(list) { list.removeAll { it.id == sessionId } }
    }

    override suspend fun listScheduleItems(potId: String): List<ScheduleItem> =
        scheduleItems[potId]?.let { list ->
            synchronized(list) {
                list.sortedWith(
                    compareBy<ScheduleItem> { it.completed }
                        .thenBy { it.dueAt ?: it.displayTime }
                        .thenBy { it.createdAt },
                )
            }
        } ?: emptyList()

    override suspend fun saveScheduleItem(item: ScheduleItem) {
        val list = scheduleItems.computeIfAbsent(item.potId) { mutableListOf() }
        synchronized(list) { list.removeAll { it.id == item.id }; list += item }
    }

    override suspend fun saveShareCode(code: ShareCode, potId: String) { shares[code.code] = potId to code }

    override suspend fun redeemShareCode(code: String, actorName: String, now: String): Pair<String, ShareCode>? {
        val value = shares[code] ?: return null
        if (Instant.parse(value.second.expiresAt).isBefore(Instant.parse(now))) return null
        shares.remove(code)
        return value
    }
}
