package com.fu1fan.smartpot.server.service

import com.fu1fan.smartpot.protocol.DeviceTelemetry
import com.fu1fan.smartpot.protocol.CareType
import com.fu1fan.smartpot.protocol.PlantMood
import com.fu1fan.smartpot.protocol.PotProfile
import com.fu1fan.smartpot.server.catalog.SpeciesCatalog
import com.fu1fan.smartpot.server.store.InMemorySmartPotStore
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WateringDetectorTest {
    @Test
    fun `sharp moisture rise creates one deletable watering care log`() = runBlocking {
        val store = InMemorySmartPotStore()
        val realtime = RealtimeHub()
        val care = CareService(store, AffinityService(store, realtime), realtime)
        val pot = PotProfile(
            id = "11111111-1111-1111-1111-111111111111",
            deviceId = "device-1",
            displayName = "小麦",
            species = SpeciesCatalog.all.first(),
            createdAt = "2026-08-02T08:00:00Z",
        )
        store.savePot(pot)
        val start = Instant.parse("2026-08-02T08:00:00Z")

        assertFalse(care.observeTelemetry(pot, telemetry(20, 100), start) != null)
        assertFalse(care.observeTelemetry(pot, telemetry(33, 105), start.plusSeconds(5)) != null)
        val detected = care.observeTelemetry(pot, telemetry(34, 110), start.plusSeconds(10))

        assertTrue(detected?.type == CareType.WATER)
        assertTrue(detected?.actorName == "自动检测")
        assertTrue(store.listCareLogs(pot.id).size == 1)
        assertTrue(care.delete(pot.id, requireNotNull(detected).id))
        assertTrue(store.listCareLogs(pot.id).isEmpty())
    }

    @Test
    fun `detects a sustained sharp moisture rise only once`() {
        val detector = WateringDetector()
        val start = Instant.parse("2026-08-02T08:00:00Z")

        assertFalse(detector.observe("pot-1", telemetry(20, 100), start))
        assertFalse(detector.observe("pot-1", telemetry(33, 105), start.plusSeconds(5)))
        assertTrue(detector.observe("pot-1", telemetry(34, 110), start.plusSeconds(10)))
        assertFalse(detector.observe("pot-1", telemetry(35, 115), start.plusSeconds(15)))
    }

    @Test
    fun `ignores a single spike and resets after device restart`() {
        val detector = WateringDetector()
        val start = Instant.parse("2026-08-02T08:00:00Z")

        assertFalse(detector.observe("pot-1", telemetry(20, 100), start))
        assertFalse(detector.observe("pot-1", telemetry(40, 105), start.plusSeconds(5)))
        assertFalse(detector.observe("pot-1", telemetry(21, 110), start.plusSeconds(10)))
        assertFalse(detector.observe("pot-1", telemetry(45, 2), start.plusSeconds(15)))
        assertFalse(detector.observe("pot-1", telemetry(46, 7), start.plusSeconds(20)))
    }

    private fun telemetry(soilPercent: Int, uptimeSeconds: Long) = DeviceTelemetry(
        deviceId = "device-1",
        sequence = uptimeSeconds,
        recordedAt = "2026-08-02T08:00:00Z",
        soilPercent = soilPercent,
        lightLux = 1_000,
        lightPercent = 50,
        touchCount = 0,
        mood = PlantMood.HAPPY,
        uptimeSeconds = uptimeSeconds,
    )
}
