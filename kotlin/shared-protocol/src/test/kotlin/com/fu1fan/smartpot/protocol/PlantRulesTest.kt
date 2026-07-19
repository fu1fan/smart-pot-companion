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
        assertEquals(2_660, PlantRules.maxAffinityPoints)
        assertEquals(45, PlantRules.initialAffinityScore)
        assertEquals(3, PlantRules.affinityLevelNumber(PlantRules.initialAffinityScore))
        assertEquals(2, PlantRules.initialAffinityState().schemaVersion)
        assertEquals(1, PlantRules.affinityLevelNumber(19))
        assertEquals(2, PlantRules.affinityLevelNumber(20))
        assertEquals(30, PlantRules.affinityLevelNumber(2_660))
        assertEquals(AffinityLevel.STRANGER, PlantRules.affinityLevel(100))
        assertEquals(AffinityLevel.SOULMATE, PlantRules.affinityLevel(2_660))
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
        assertEquals(5f / 3f, PlantRules.companionStars(dailyInteractions = 5), 0.001f)
        assertEquals(5f, PlantRules.companionStars(dailyInteractions = 15), 0.001f)
        assertEquals(1.0, PlantRules.interactionSuitability(dailyInteractions = 12), 0.001)
    }
}
