package com.fu1fan.smartpot.server

import com.fu1fan.smartpot.protocol.CareWeather
import com.fu1fan.smartpot.protocol.CreateFocusSessionRequest
import com.fu1fan.smartpot.protocol.DailyFocusSummary
import com.fu1fan.smartpot.protocol.DeviceTelemetry
import com.fu1fan.smartpot.protocol.FocusSession
import com.fu1fan.smartpot.protocol.PotProfile
import com.fu1fan.smartpot.server.store.SmartPotStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.roundToInt

private const val PomodoroMinutes = 25
private const val DailyTargetPomodoros = 4

internal suspend fun focusSummaries(
    store: SmartPotStore,
    pot: PotProfile,
    days: Int,
    today: LocalDate = LocalDate.now(zoneIdOf(pot.timezone)),
): List<DailyFocusSummary> {
    val zone = zoneIdOf(pot.timezone)
    val firstDate = today.minusDays((days - 1).toLong())
    val since = firstDate.atStartOfDay(zone).toInstant().toString()
    val sessions = store.listFocusSessions(pot.id, since)
    return (0 until days).map { offset ->
        val date = firstDate.plusDays(offset.toLong())
        val daily = sessions.filter { it.completedAt.toLocalDate(zone) == date }
        val count = daily.sumOf { (it.minutes / PomodoroMinutes).coerceAtLeast(1) }
        val minutes = daily.sumOf { it.minutes }
        val completion = ((count.toDouble() / DailyTargetPomodoros) * 100).roundToInt().coerceIn(0, 100)
        DailyFocusSummary(date.toString(), count, minutes, DailyTargetPomodoros, completion)
    }
}

internal fun focusSessionFrom(pot: PotProfile, request: CreateFocusSessionRequest): FocusSession {
    val minutes = request.minutes.coerceIn(PomodoroMinutes, PomodoroMinutes * 8)
    return FocusSession(
        id = UUID.randomUUID().toString(),
        potId = pot.id,
        completedAt = request.completedAt?.takeIf { it.isNotBlank() } ?: Instant.now().toString(),
        minutes = minutes,
        source = request.source.trim().ifBlank { "APP" }.take(24),
    )
}

internal fun weatherFor(date: LocalDate, telemetry: List<DeviceTelemetry>, pot: PotProfile): CareWeather {
    val averageLux = telemetry.map { it.lightLux }.average().takeIf { !it.isNaN() }?.roundToInt()
    val maxLux = telemetry.maxOfOrNull { it.lightLux }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
    val threshold = pot.species.thresholds
    val condition = when {
        averageLux == null -> "待同步"
        maxLux != null && maxLux > threshold.lightMaxLux -> "强日照"
        averageLux >= threshold.lightMinLux -> "晴朗"
        averageLux >= threshold.lightMinLux / 3 -> "多云"
        else -> "阴天"
    }
    val hint = when (condition) {
        "强日照" -> "光照偏强，留意叶片晒伤"
        "晴朗" -> "光照充足，适合观察新芽"
        "多云" -> "光线柔和，保持通风"
        "阴天" -> "光照偏弱，可移到明亮散射光处"
        else -> "等待设备同步今日光照"
    }
    return CareWeather(date.toString(), condition, averageLux, maxLux, hint)
}

internal fun String.toLocalDate(zone: ZoneId): LocalDate? =
    runCatching { Instant.parse(this).atZone(zone).toLocalDate() }.getOrNull()

internal fun zoneIdOf(value: String): ZoneId =
    runCatching { ZoneId.of(value) }.getOrDefault(ZoneId.of("Asia/Shanghai"))
