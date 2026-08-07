package com.fu1fan.smartpot.server.service

import com.fu1fan.smartpot.protocol.DeviceEventType
import com.fu1fan.smartpot.server.appJson
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MqttGatewayTopicTest {
    @Test
    fun `accepts every motion event emitted by the device`() {
        listOf("PHYSICAL_TAP", "SHAKE", "FALLEN", "FALL_RECOVERED").forEach { value ->
            assertEquals(DeviceEventType.valueOf(value), appJson.decodeFromString<DeviceEventType>("\"$value\""))
        }
    }

    @Test
    fun `parses subscribed device topic`() {
        val parsed = parseDeviceTopic("smartpot/v1/devices/smartpot-p4-001/telemetry")

        assertEquals("smartpot-p4-001", parsed.deviceId)
        assertEquals("telemetry", parsed.kind)
    }

    @Test
    fun `rejects malformed device topic`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseDeviceTopic("smartpot/v1/devices/smartpot-p4-001/status/telemetry")
        }
    }
}
