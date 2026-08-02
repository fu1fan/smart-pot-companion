package com.fu1fan.smartpot.server.service

import com.fu1fan.smartpot.protocol.DeviceTelemetry
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

internal class WateringDetector(
    private val riseThresholdPercent: Int = 12,
    private val confirmationSamples: Int = 2,
    private val window: Duration = Duration.ofMinutes(10),
) {
    private data class Reading(val observedAt: Instant, val soilPercent: Int)

    private class PotState {
        val readings = ArrayDeque<Reading>()
        var consecutiveHighSamples = 0
        var lastObservedAt: Instant? = null
        var lastUptimeSeconds: Long? = null
    }

    private val states = ConcurrentHashMap<String, PotState>()

    fun observe(potId: String, telemetry: DeviceTelemetry, observedAt: Instant): Boolean {
        val state = states.computeIfAbsent(potId) { PotState() }
        return synchronized(state) {
            val lastObservedAt = state.lastObservedAt
            val lastUptime = state.lastUptimeSeconds
            val deviceRestarted = lastUptime != null && telemetry.uptimeSeconds < lastUptime
            val observationGap = lastObservedAt?.let { Duration.between(it, observedAt) }
            if (deviceRestarted || observationGap == null || observationGap.isNegative || observationGap > window) {
                state.readings.clear()
                state.consecutiveHighSamples = 0
            }

            val cutoff = observedAt.minus(window)
            while (state.readings.firstOrNull()?.observedAt?.isBefore(cutoff) == true) {
                state.readings.removeFirst()
            }

            val baseline = state.readings.minOfOrNull(Reading::soilPercent)
            val roseSharply = baseline != null && telemetry.soilPercent - baseline >= riseThresholdPercent
            state.consecutiveHighSamples = if (roseSharply) state.consecutiveHighSamples + 1 else 0

            state.lastObservedAt = observedAt
            state.lastUptimeSeconds = telemetry.uptimeSeconds
            state.readings.addLast(Reading(observedAt, telemetry.soilPercent))

            if (state.consecutiveHighSamples >= confirmationSamples) {
                state.readings.clear()
                state.readings.addLast(Reading(observedAt, telemetry.soilPercent))
                state.consecutiveHighSamples = 0
                true
            } else {
                false
            }
        }
    }
}
