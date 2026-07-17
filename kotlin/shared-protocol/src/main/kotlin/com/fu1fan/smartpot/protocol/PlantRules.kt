package com.fu1fan.smartpot.protocol

import kotlin.math.roundToInt

object PlantRules {
    private const val DAILY_FULL_INTERACTIONS = 10

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
        (dailyInteractions.coerceAtLeast(0) / DAILY_FULL_INTERACTIONS.toDouble()).coerceIn(0.0, 1.0)

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
        (interactionSuitability(dailyInteractions) * 5.0).toFloat()

    fun affinityLevel(score: Int): AffinityLevel = when (score.coerceIn(0, 100)) {
        in 0..19 -> AffinityLevel.STRANGER
        in 20..39 -> AffinityLevel.FAMILIAR
        in 40..59 -> AffinityLevel.CLOSE
        in 60..79 -> AffinityLevel.TRUSTED
        else -> AffinityLevel.BEST_FRIEND
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
