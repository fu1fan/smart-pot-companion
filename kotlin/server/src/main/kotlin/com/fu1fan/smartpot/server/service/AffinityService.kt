package com.fu1fan.smartpot.server.service

import com.fu1fan.smartpot.protocol.AffinityState
import com.fu1fan.smartpot.protocol.PlantRules
import com.fu1fan.smartpot.protocol.RealtimeEvent
import com.fu1fan.smartpot.protocol.RealtimeEventType
import com.fu1fan.smartpot.server.appJson
import com.fu1fan.smartpot.server.store.SmartPotStore
import kotlinx.serialization.json.encodeToJsonElement
import java.time.Instant
import java.time.ZoneId

class AffinityService(
    private val store: SmartPotStore,
    private val realtime: RealtimeHub,
) {
    suspend fun award(potId: String, eventKey: String, points: Int, at: Instant = Instant.now()): AffinityState {
        val pot = requireNotNull(store.findPot(potId)) { "盆栽不存在" }
        val zone = runCatching { ZoneId.of(pot.timezone) }.getOrDefault(ZoneId.of("Asia/Shanghai"))
        val dayStart = at.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toString()
        val requested = points.coerceIn(-12, 15)
        val category = positiveCategory(eventKey)
        val categoryRemaining = category?.let { (prefix, cap) ->
            cap - store.sumAffinityEventPoints(potId, prefix, dayStart, positiveOnly = true)
        } ?: Int.MAX_VALUE
        val dailyRemaining = if (requested >= 0) {
            25 - store.sumAffinityEventPoints(potId, null, dayStart, positiveOnly = true)
        } else {
            12 + store.sumAffinityEventPoints(potId, null, dayStart, positiveOnly = false)
        }
        val applied = if (requested >= 0) {
            requested.coerceAtMost(categoryRemaining.coerceAtLeast(0)).coerceAtMost(dailyRemaining.coerceAtLeast(0))
        } else {
            -((-requested).coerceAtMost(dailyRemaining.coerceAtLeast(0)))
        }
        if (!store.addAffinityEvent(potId, eventKey, applied, at.toString())) return store.affinity(potId)
        val old = PlantRules.normalizeAffinity(store.affinity(potId))
        if (applied == 0) return old
        val score = (old.score + applied).coerceIn(0, PlantRules.maxAffinityPoints)
        val updated = AffinityState(score, PlantRules.affinityLevel(score), at.toString(), schemaVersion = 2)
        store.saveAffinity(potId, updated)
        realtime.publish(RealtimeEvent(RealtimeEventType.AFFINITY, potId, appJson.encodeToJsonElement(updated)))
        return updated
    }

    suspend fun revoke(potId: String, eventKey: String, at: Instant = Instant.now()): AffinityState {
        val removedPoints = store.removeAffinityEvent(potId, eventKey) ?: return store.affinity(potId)
        val old = PlantRules.normalizeAffinity(store.affinity(potId))
        val score = (old.score - removedPoints).coerceIn(0, PlantRules.maxAffinityPoints)
        val updated = AffinityState(score, PlantRules.affinityLevel(score), at.toString(), schemaVersion = 2)
        store.saveAffinity(potId, updated)
        realtime.publish(RealtimeEvent(RealtimeEventType.AFFINITY, potId, appJson.encodeToJsonElement(updated)))
        return updated
    }

    private fun positiveCategory(eventKey: String): Pair<String, Int>? = when {
        eventKey.startsWith("chat:") -> "chat:" to 5
        eventKey.startsWith("device-event:") -> "device-event:" to 3
        eventKey.startsWith("focus:") -> "focus:" to 4
        eventKey.startsWith("schedule:") -> "schedule:" to 3
        eventKey.startsWith("diary:") -> "diary:" to 1
        else -> null
    }
}
