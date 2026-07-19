package com.fu1fan.smartpot.protocol

import kotlin.math.roundToInt

object PlantRules {
    private const val DAILY_HEALTH_FULL_INTERACTIONS = 10
    private const val DAILY_COMPANION_FULL_INTERACTIONS = 15
    val affinityPointsPerLevel = listOf(
        20, 25, 30, 35, 40, 45, 50, 55, 60, 65,
        70, 75, 80, 85, 90, 95, 100, 105, 110, 115,
        120, 125, 130, 135, 140, 150, 160, 170, 180,
    )
    val maxAffinityPoints: Int = affinityPointsPerLevel.sum()

    fun evaluate(telemetry: DeviceTelemetry, thresholds: PlantThresholds): EvaluatedPlantState {
        val soil = when {
            telemetry.soilPercent < thresholds.soilMinPercent -> SoilStatus.TOO_DRY
            telemetry.soilPercent > thresholds.soilMaxPercent -> SoilStatus.TOO_WET
            else -> SoilStatus.SUITABLE
        }
        val light = when {
            telemetry.lightLux < thresholds.lightMinLux -> LightStatus.DARK
            telemetry.lightLux > thresholds.lightMaxLux -> LightStatus.TOO_STRONG
            else -> LightStatus.DIFFUSE
        }
        return EvaluatedPlantState(
            soilStatus = soil,
            lightStatus = light,
            soilAdvice = when (soil) {
                SoilStatus.TOO_DRY -> "土壤偏干，建议检查后适量浇水"
                SoilStatus.SUITABLE -> "土壤湿度适宜"
                SoilStatus.TOO_WET -> "盆土过湿，暂停浇水并加强通风"
                SoilStatus.UNKNOWN -> "暂无土壤数据"
            },
            lightAdvice = when (light) {
                LightStatus.DARK -> "光照不足，建议移到更明亮的散射光位置"
                LightStatus.DIFFUSE -> "当前光照适宜"
                LightStatus.TOO_STRONG -> "光照过强，建议避开直射光"
                LightStatus.UNKNOWN -> "暂无光照数据"
            },
        )
    }

    fun soilSuitability(soilPercent: Int, thresholds: PlantThresholds): Double =
        rangeSuitability(
            value = soilPercent.toDouble(),
            min = thresholds.soilMinPercent.toDouble(),
            max = thresholds.soilMaxPercent.toDouble(),
        )

    fun lightSuitability(lightLux: Long, thresholds: PlantThresholds): Double =
        rangeSuitability(
            value = lightLux.toDouble(),
            min = thresholds.lightMinLux.toDouble(),
            max = thresholds.lightMaxLux.toDouble(),
        )

    fun interactionSuitability(dailyInteractions: Int): Double =
        (dailyInteractions.coerceAtLeast(0) / DAILY_HEALTH_FULL_INTERACTIONS.toDouble()).coerceIn(0.0, 1.0)

    fun healthPercent(
        telemetry: DeviceTelemetry,
        thresholds: PlantThresholds,
        dailyInteractions: Int,
    ): Int {
        val health = 0.4 * soilSuitability(telemetry.soilPercent, thresholds) +
            0.4 * lightSuitability(telemetry.lightLux, thresholds) +
            0.2 * interactionSuitability(dailyInteractions)
        return (health * 100).roundToInt().coerceIn(0, 100)
    }

    fun companionStars(dailyInteractions: Int): Float =
        (dailyInteractions.coerceAtLeast(0) / DAILY_COMPANION_FULL_INTERACTIONS.toFloat() * 5f).coerceIn(0f, 5f)

    fun affinityLevelNumber(score: Int): Int {
        val safeScore = score.coerceIn(0, maxAffinityPoints)
        var cumulative = 0
        affinityPointsPerLevel.forEachIndexed { index, required ->
            cumulative += required
            if (safeScore < cumulative) return index + 1
        }
        return 30
    }

    fun affinityLevel(score: Int): AffinityLevel = when (affinityLevelNumber(score)) {
        in 1..5 -> AffinityLevel.STRANGER
        in 6..10 -> AffinityLevel.FAMILIAR
        in 11..15 -> AffinityLevel.CLOSE
        in 16..20 -> AffinityLevel.TRUSTED
        in 21..25 -> AffinityLevel.BEST_FRIEND
        in 26..29 -> AffinityLevel.LONG_TERM_COMPANION
        else -> AffinityLevel.SOULMATE
    }

    fun affinityLevelStart(score: Int): Int = affinityPointsPerLevel
        .take((affinityLevelNumber(score) - 1).coerceAtLeast(0))
        .sum()

    fun affinityPointsToNextLevel(score: Int): Int {
        val safeScore = score.coerceIn(0, maxAffinityPoints)
        if (safeScore >= maxAffinityPoints) return 0
        val level = affinityLevelNumber(safeScore)
        return affinityPointsPerLevel.take(level).sum() - safeScore
    }

    fun affinityLevelProgress(score: Int): Float {
        val safeScore = score.coerceIn(0, maxAffinityPoints)
        if (safeScore >= maxAffinityPoints) return 1f
        val level = affinityLevelNumber(safeScore)
        val start = affinityPointsPerLevel.take(level - 1).sum()
        return ((safeScore - start).toFloat() / affinityPointsPerLevel[level - 1]).coerceIn(0f, 1f)
    }

    fun normalizeAffinity(state: AffinityState): AffinityState {
        if (state.schemaVersion >= 2) return state.copy(
            score = state.score.coerceIn(0, maxAffinityPoints),
            level = affinityLevel(state.score),
        )
        val migratedScore = (state.score.coerceIn(0, 100) * maxAffinityPoints / 100.0).roundToInt()
        return AffinityState(
            score = migratedScore,
            level = affinityLevel(migratedScore),
            updatedAt = state.updatedAt,
            schemaVersion = 2,
        )
    }

    private fun rangeSuitability(value: Double, min: Double, max: Double): Double {
        if (max <= min || value.isNaN()) return 0.0
        return when {
            value in min..max -> 1.0
            value < min -> if (min <= 0.0) 0.0 else value / min
            else -> if (value <= 0.0) 0.0 else max / value
        }.coerceIn(0.0, 1.0)
    }
}
