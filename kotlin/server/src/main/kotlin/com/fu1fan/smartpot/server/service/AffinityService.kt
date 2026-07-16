package com.fu1fan.smartpot.server.service

import com.fu1fan.smartpot.protocol.AffinityState
import com.fu1fan.smartpot.protocol.PlantRules
import com.fu1fan.smartpot.protocol.RealtimeEvent
import com.fu1fan.smartpot.protocol.RealtimeEventType
import com.fu1fan.smartpot.server.appJson
import com.fu1fan.smartpot.server.store.SmartPotStore
import kotlinx.serialization.json.encodeToJsonElement
import java.time.Instant

class AffinityService(
    private val store: SmartPotStore,
    private val realtime: RealtimeHub,
) {
    suspend fun award(potId: String, eventKey: String, points: Int, at: Instant = Instant.now()): AffinityState {
        if (!store.addAffinityEvent(potId, eventKey, points.coerceIn(-10, 10), at.toString())) return store.affinity(potId)
        val old = store.affinity(potId)
        val score = (old.score + points).coerceIn(0, 100)
        val updated = AffinityState(score, PlantRules.affinityLevel(score), at.toString())
        store.saveAffinity(potId, updated)
        realtime.publish(RealtimeEvent(RealtimeEventType.AFFINITY, potId, appJson.encodeToJsonElement(updated)))
        return updated
    }
}
