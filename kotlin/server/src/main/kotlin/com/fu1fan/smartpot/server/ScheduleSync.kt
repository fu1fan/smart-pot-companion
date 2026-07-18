package com.fu1fan.smartpot.server

import com.fu1fan.smartpot.protocol.CreateScheduleItemRequest
import com.fu1fan.smartpot.protocol.DeviceScheduleItem
import com.fu1fan.smartpot.protocol.DeviceCommandType
import com.fu1fan.smartpot.protocol.DeviceControlRequest
import com.fu1fan.smartpot.protocol.PotProfile
import com.fu1fan.smartpot.protocol.ScheduleItem
import com.fu1fan.smartpot.protocol.ScheduleSyncState
import com.fu1fan.smartpot.protocol.UpdateScheduleItemRequest
import com.fu1fan.smartpot.server.service.CommandService
import com.fu1fan.smartpot.server.store.SmartPotStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.roundToInt

private const val MaxSyncedScheduleItems = 8

internal fun scheduleState(items: List<ScheduleItem>): ScheduleSyncState =
    ScheduleSyncState(scheduleRevision(items), items)

internal fun scheduleRevision(items: List<ScheduleItem>): Long =
    items.maxOfOrNull { runCatching { Instant.parse(it.updatedAt).toEpochMilli() }.getOrDefault(0L) } ?: 0L

internal fun scheduleItemFrom(
    pot: PotProfile,
    request: CreateScheduleItemRequest,
    source: String,
    now: Instant = Instant.now(),
): ScheduleItem {
    val title = request.title.trim()
    require(title.isNotBlank() && title.length <= 80) { "日程内容应为 1-80 个字符" }
    val dueAt = request.dueAt?.trim()?.takeIf { it.isNotBlank() }?.also {
        require(runCatching { Instant.parse(it) }.isSuccess) { "日程时间格式应为 ISO-8601" }
    }
    val displayTime = request.displayTime.trim().take(40)
    return ScheduleItem(
        id = UUID.randomUUID().toString(),
        potId = pot.id,
        title = title,
        dueAt = dueAt,
        displayTime = displayTime,
        source = source.trim().ifBlank { "APP" }.take(24),
        createdAt = now.toString(),
        updatedAt = now.toString(),
    )
}

internal fun updatedScheduleItem(
    current: ScheduleItem,
    request: UpdateScheduleItemRequest,
    now: Instant = Instant.now(),
): ScheduleItem {
    val title = request.title?.trim()?.also { require(it.isNotBlank() && it.length <= 80) { "日程内容应为 1-80 个字符" } }
    val dueAt = request.dueAt?.trim()?.takeIf { it.isNotBlank() }?.also {
        require(runCatching { Instant.parse(it) }.isSuccess) { "日程时间格式应为 ISO-8601" }
    }
    val displayTime = request.displayTime?.trim()?.take(40)
    val completed = request.completed ?: current.completed
    return current.copy(
        title = title ?: current.title,
        dueAt = dueAt ?: current.dueAt,
        displayTime = displayTime ?: current.displayTime,
        completed = completed,
        completedAt = when {
            completed && !current.completed -> now.toString()
            !completed -> null
            else -> current.completedAt
        },
        updatedAt = now.toString(),
    )
}

internal suspend fun CommandService.syncSchedule(pot: PotProfile, items: List<ScheduleItem>) =
    submit(
        pot,
        DeviceControlRequest(
            type = DeviceCommandType.SYNC_SCHEDULE,
            scheduleRevision = scheduleRevision(items),
            scheduleItems = items
                .sortedWith(compareBy<ScheduleItem> { it.completed }.thenBy { it.dueAt ?: it.displayTime }.thenBy { it.createdAt })
                .take(MaxSyncedScheduleItems),
        ),
    )

internal suspend fun CommandService.syncProfile(pot: PotProfile) =
    submit(pot, DeviceControlRequest(type = DeviceCommandType.SYNC_PROFILE))

internal suspend fun syncScheduleToDevice(store: SmartPotStore, commands: CommandService, pot: PotProfile) {
    runCatching { commands.syncSchedule(pot, store.listScheduleItems(pot.id)) }
        .onFailure { System.err.println("Schedule sync skipped: ${it.message}") }
}

internal suspend fun mergeDeviceScheduleItems(
    store: SmartPotStore,
    pot: PotProfile,
    items: List<DeviceScheduleItem>,
    now: Instant = Instant.now(),
): Boolean {
    if (items.isEmpty()) return false
    val nowText = now.toString()
    val existing = store.listScheduleItems(pot.id).toMutableList()
    var changed = false

    items.take(MaxSyncedScheduleItems).forEach { reported ->
        val title = reported.title.trim().take(80)
        if (title.isBlank()) return@forEach
        val displayTime = reported.displayTime.trim().take(40)
        val dueAt = reported.dueAt?.trim()?.takeIf { it.isNotBlank() && runCatching { Instant.parse(it) }.isSuccess }
            ?: reported.dueAtEpochSeconds?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).toString() }
        val reportedId = reported.id.trim().validUuidOrNull()
        val current = reportedId?.let { id -> existing.firstOrNull { it.id == id } }
            ?: existing.firstOrNull { item ->
                item.title == title &&
                    item.displayTime == displayTime &&
                    item.dueAt == dueAt
            }

        val next = current?.let { item ->
            val source = if (item.source == "APP") item.source else "ESP"
            val candidate = item.copy(
                title = title,
                dueAt = dueAt ?: item.dueAt,
                displayTime = displayTime.ifBlank { item.displayTime },
                completed = reported.completed,
                completedAt = when {
                    reported.completed -> item.completedAt ?: nowText
                    else -> null
                },
                source = source,
            )
            if (candidate == item) item else candidate.copy(updatedAt = nowText)
        } ?: ScheduleItem(
            id = reportedId ?: UUID.randomUUID().toString(),
            potId = pot.id,
            title = title,
            dueAt = dueAt,
            displayTime = displayTime,
            completed = reported.completed,
            completedAt = if (reported.completed) nowText else null,
            source = "ESP",
            createdAt = nowText,
            updatedAt = nowText,
        )

        if (next != current) {
            store.saveScheduleItem(next)
            current?.let { existing.remove(it) }
            existing += next
            changed = true
        }
    }
    return changed
}

internal fun potGrowthDays(pot: PotProfile, now: Instant = Instant.now()): Int {
    val zone = runCatching { ZoneId.of(pot.timezone) }.getOrDefault(ZoneId.of("Asia/Shanghai"))
    val createdDate = runCatching { Instant.parse(pot.createdAt).atZone(zone).toLocalDate() }
        .getOrElse { now.atZone(zone).toLocalDate() }
    val today = now.atZone(zone).toLocalDate()
    return (ChronoUnit.DAYS.between(createdDate, today).toInt() + 1).coerceAtLeast(1)
}

internal fun scheduleCompletionPercent(items: List<ScheduleItem>, date: LocalDate, zone: ZoneId): Int? {
    val daily = items.filter { item ->
        (item.dueAt ?: item.createdAt).toLocalDate(zone) == date
    }
    if (daily.isEmpty()) return null
    return ((daily.count { it.completed }.toDouble() / daily.size) * 100).roundToInt().coerceIn(0, 100)
}

private fun String.validUuidOrNull(): String? {
    val value = trim()
    return value.takeIf { it.isNotBlank() && runCatching { UUID.fromString(value) }.isSuccess }
}
