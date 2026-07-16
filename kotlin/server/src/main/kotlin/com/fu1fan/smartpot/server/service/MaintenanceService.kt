package com.fu1fan.smartpot.server.service

import com.fu1fan.smartpot.server.store.SmartPotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.hours

class MaintenanceService(private val store: SmartPotStore) {
    fun start(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            store.pruneTelemetryBefore(Instant.now().minus(90, ChronoUnit.DAYS).toString())
            delay(24.hours)
        }
    }
}
