package com.fu1fan.smartpot.server.service

import com.fu1fan.smartpot.protocol.*
import com.fu1fan.smartpot.server.appJson
import com.fu1fan.smartpot.server.store.SmartPotStore
import kotlinx.serialization.json.encodeToJsonElement
import java.time.Instant
import java.util.UUID

class PotService(
    private val store: SmartPotStore,
    private val realtime: RealtimeHub,
) {
    suspend fun create(request: CreatePotRequest): PotProfile {
        require(request.deviceId.matches(Regex("[A-Za-z0-9_-]{3,64}"))) { "设备 ID 格式无效" }
        require(request.displayName.isNotBlank() && request.displayName.length <= 32) { "盆栽名称应为 1-32 个字符" }
        require(store.findPotByDevice(request.deviceId) == null) { "设备已经绑定" }
        val species = requireNotNull(store.findSpecies(request.speciesId)) { "植物品种不存在" }
        return store.savePot(
            PotProfile(
                id = UUID.randomUUID().toString(),
                deviceId = request.deviceId,
                displayName = request.displayName,
                species = species,
                createdAt = Instant.now().toString(),
            ),
        )
    }

    suspend fun update(id: String, request: UpdatePotRequest): PotProfile {
        val current = requireNotNull(findCurrent(id)) { "盆栽不存在" }
        val species = request.speciesId?.let { requireNotNull(store.findSpecies(it)) { "植物品种不存在" } } ?: current.species
        val name = request.displayName?.also { require(it.isNotBlank() && it.length <= 32) } ?: current.displayName
        val updated = store.savePot(current.copy(displayName = name, species = species))
        publishSnapshot(updated.id)
        return updated
    }

    suspend fun ensureForDevice(deviceId: String): PotProfile {
        store.findPotByDevice(deviceId)?.let { return refreshSpecies(it) }
        val species = requireNotNull(store.findSpecies("pothos"))
        return store.savePot(
            PotProfile(
                id = UUID.randomUUID().toString(),
                deviceId = deviceId,
                displayName = "小麦的绿植",
                species = species,
                createdAt = Instant.now().toString(),
            ),
        )
    }

    suspend fun findCurrent(id: String): PotProfile? = store.findPot(id)?.let { refreshSpecies(it) }

    suspend fun listCurrent(): List<PotProfile> = store.listPots().map { refreshSpecies(it) }

    suspend fun snapshot(id: String): PotSnapshot {
        val pot = requireNotNull(findCurrent(id)) { "盆栽不存在" }
        val telemetry = store.latestTelemetry(id)
        val state = store.deviceState(pot.deviceId)
        return PotSnapshot(
            pot = pot,
            telemetry = telemetry,
            deviceState = state.reported,
            online = state.online,
            lastSeenAt = state.lastSeenAt,
            evaluated = telemetry?.let { PlantRules.evaluate(it, pot.species.thresholds) },
            activeAlerts = store.listAlerts(id, activeOnly = true),
            affinity = store.affinity(id),
        )
    }

    suspend fun publishSnapshot(id: String) {
        realtime.publish(RealtimeEvent(RealtimeEventType.SNAPSHOT, id, appJson.encodeToJsonElement(snapshot(id))))
    }

    private suspend fun refreshSpecies(pot: PotProfile): PotProfile {
        val latest = store.findSpecies(pot.species.id) ?: return pot
        if (latest == pot.species) return pot
        return store.savePot(pot.copy(species = latest))
    }
}
