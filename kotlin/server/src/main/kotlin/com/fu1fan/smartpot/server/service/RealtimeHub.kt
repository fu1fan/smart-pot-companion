package com.fu1fan.smartpot.server.service

import com.fu1fan.smartpot.protocol.RealtimeEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentHashMap

class RealtimeHub {
    private val streams = ConcurrentHashMap<String, MutableSharedFlow<RealtimeEvent>>()

    fun stream(potId: String): SharedFlow<RealtimeEvent> = streams.computeIfAbsent(potId) {
        MutableSharedFlow(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }

    fun publish(event: RealtimeEvent) {
        streams.computeIfAbsent(event.potId) {
            MutableSharedFlow(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }.tryEmit(event)
    }
}
