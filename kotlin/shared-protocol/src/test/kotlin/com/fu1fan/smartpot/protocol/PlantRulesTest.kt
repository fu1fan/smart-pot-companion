package com.fu1fan.smartpot.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlantRulesTest {
    private val thresholds = PlantThresholds(35, 70, 500, 8_000)

    @Test
    fun `classifies dry and dark telemetry`() {
        val telemetry = DeviceTelemetry(
            deviceId = "demo",
            sequence = 1,
            recordedAt = "2026-07-16T10:00:00Z",
            soilPercent = 20,
            lightLux = 100,
            lightPercent = 10,
            touchCount = 0,
            mood = PlantMood.WEAK,
            uptimeSeconds = 10,
        )

        val result = PlantRules.evaluate(telemetry, thresholds)

        assertEquals(SoilStatus.TOO_DRY, result.soilStatus)
        assertEquals(LightStatus.DARK, result.lightStatus)
    }

    @Test
    fun `maps affinity boundaries`() {
        assertEquals(AffinityLevel.STRANGER, PlantRules.affinityLevel(19))
        assertEquals(AffinityLevel.FAMILIAR, PlantRules.affinityLevel(20))
        assertEquals(AffinityLevel.BEST_FRIEND, PlantRules.affinityLevel(100))
    }
}
