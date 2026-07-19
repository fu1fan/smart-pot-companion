package com.fu1fan.smartpot.server.service

import com.fu1fan.smartpot.protocol.*
import com.fu1fan.smartpot.server.appJson
import com.fu1fan.smartpot.server.store.SmartPotStore
import kotlinx.serialization.json.encodeToJsonElement
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AlertService(
    private val store: SmartPotStore,
    private val realtime: RealtimeHub,
    private val affinity: AffinityService,
    private val sustainDuration: Duration = Duration.ofMinutes(10),
) {
    private val pendingSince = ConcurrentHashMap<String, Instant>()

    suspend fun evaluate(pot: PotProfile, telemetry: DeviceTelemetry, now: Instant = Instant.now()) {
        val result = PlantRules.evaluate(telemetry, pot.species.thresholds)
        val abnormal = buildSet {
            when (result.soilStatus) {
                SoilStatus.TOO_DRY -> add(AlertType.SOIL_DRY)
                SoilStatus.TOO_WET -> add(AlertType.SOIL_WET)
                else -> Unit
            }
            when (result.lightStatus) {
                LightStatus.DARK -> add(AlertType.LIGHT_LOW)
                LightStatus.TOO_STRONG -> add(AlertType.LIGHT_HIGH)
                else -> Unit
            }
            if ((telemetry.motion?.tiltLevel ?: 0) >= 2) add(AlertType.TILT_SEVERE)
        }
        AlertType.entries.filter { it != AlertType.DEVICE_OFFLINE }.forEach { type ->
            val key = "${pot.id}:$type"
            val active = store.listAlerts(pot.id, true).firstOrNull { it.type == type }
            if (type in abnormal) {
                val since = pendingSince.putIfAbsent(key, now) ?: pendingSince[key] ?: now
                val immediate = type == AlertType.TILT_SEVERE
                if (active == null && (immediate || Duration.between(since, now) >= sustainDuration)) {
                    val alert = PlantAlert(UUID.randomUUID().toString(), pot.id, type, AlertStatus.ACTIVE, message(type), since.toString())
                    store.saveAlert(alert)
                    realtime.publish(RealtimeEvent(RealtimeEventType.ALERT, pot.id, appJson.encodeToJsonElement(alert)))
                    val points = when (type) {
                        AlertType.SOIL_DRY, AlertType.LIGHT_LOW -> -2
                        AlertType.SOIL_WET, AlertType.LIGHT_HIGH -> -1
                        AlertType.TILT_SEVERE -> -2
                        AlertType.DEVICE_OFFLINE -> 0
                    }
                    if (points != 0) affinity.award(pot.id, "penalty:${type.name}:${now.toString().take(13)}", points, now)
                }
            } else {
                pendingSince.remove(key)
                if (active != null) {
                    val recovered = active.copy(status = AlertStatus.RECOVERED, recoveredAt = now.toString())
                    store.saveAlert(recovered)
                    realtime.publish(RealtimeEvent(RealtimeEventType.ALERT, pot.id, appJson.encodeToJsonElement(recovered)))
                    val points = when (type) {
                        AlertType.LIGHT_LOW, AlertType.LIGHT_HIGH -> 2
                        AlertType.TILT_SEVERE -> 1
                        else -> 0
                    }
                    if (points > 0) affinity.award(pot.id, "recovered:${active.id}", points, now)
                }
            }
        }
    }

    private fun message(type: AlertType) = when (type) {
        AlertType.SOIL_DRY -> "土壤持续偏干，请检查是否需要浇水"
        AlertType.SOIL_WET -> "盆土持续过湿，可能存在积水烂根风险"
        AlertType.LIGHT_LOW -> "光照持续不足，建议移动到明亮散射光位置"
        AlertType.LIGHT_HIGH -> "光照持续过强，存在叶片灼伤风险"
        AlertType.DEVICE_OFFLINE -> "设备已离线"
        AlertType.TILT_SEVERE -> "检测到盆栽严重倾斜或倾倒"
    }
}
