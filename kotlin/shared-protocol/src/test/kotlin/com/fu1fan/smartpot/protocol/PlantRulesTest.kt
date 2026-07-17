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

    @Test
    fun `calculates health and companion score from telemetry and interactions`() {
        val telemetry = DeviceTelemetry(
            deviceId = "demo",
            sequence = 2,
            recordedAt = "2026-07-16T10:05:00Z",
            soilPercent = 50,
            lightLux = 1_200,
            lightPercent = 40,
            touchCount = 6,
            mood = PlantMood.HAPPY,
            uptimeSeconds = 20,
        )

        assertEquals(90, PlantRules.healthPercent(telemetry, thresholds, dailyInteractions = 5))
        assertEquals(2.5f, PlantRules.companionStars(dailyInteractions = 5), 0.001f)
        assertEquals(1.0, PlantRules.interactionSuitability(dailyInteractions = 12), 0.001)
    }
}
