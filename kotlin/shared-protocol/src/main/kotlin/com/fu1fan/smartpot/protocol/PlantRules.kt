package com.fu1fan.smartpot.protocol

object PlantRules {
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

    fun affinityLevel(score: Int): AffinityLevel = when (score.coerceIn(0, 100)) {
        in 0..19 -> AffinityLevel.STRANGER
        in 20..39 -> AffinityLevel.FAMILIAR
        in 40..59 -> AffinityLevel.CLOSE
        in 60..79 -> AffinityLevel.TRUSTED
        else -> AffinityLevel.BEST_FRIEND
    }
}
