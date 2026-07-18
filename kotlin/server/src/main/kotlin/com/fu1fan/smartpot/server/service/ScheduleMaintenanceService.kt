package com.fu1fan.smartpot.server.service

import com.fu1fan.smartpot.protocol.RealtimeEvent
import com.fu1fan.smartpot.protocol.RealtimeEventType
import com.fu1fan.smartpot.server.appJson
import com.fu1fan.smartpot.server.scheduleState
import com.fu1fan.smartpot.server.store.SmartPotStore
import com.fu1fan.smartpot.server.syncSchedule
import com.fu1fan.smartpot.server.visibleScheduleItems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.encodeToJsonElement
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

class ScheduleMaintenanceService(
    private val store: SmartPotStore,
    private val commands: CommandService,
    private val realtime: RealtimeHub,
) {
    private val visibleFingerprints = ConcurrentHashMap<String, String>()

    fun start(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            val now = Instant.now()
            store.listPots().forEach { pot ->
                val allItems = store.listScheduleItems(pot.id)
                val visible = visibleScheduleItems(allItems, now)
                val fingerprint = visible.joinToString("|") { "${it.id}:${it.updatedAt}" }
                val previous = visibleFingerprints.put(pot.id, fingerprint)
                if (previous != null && previous != fingerprint) {
                    realtime.publish(
                        RealtimeEvent(
                            RealtimeEventType.SCHEDULE,
                            pot.id,
                            appJson.encodeToJsonElement(scheduleState(allItems, now)),
                        ),
                    )
                    runCatching { commands.syncSchedule(pot, allItems) }
                        .onFailure { System.err.println("Schedule expiry sync skipped: ${it.message}") }
                }
            }
            delay(1.seconds)
        }
    }
}
