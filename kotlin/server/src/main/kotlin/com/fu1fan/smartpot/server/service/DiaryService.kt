package com.fu1fan.smartpot.server.service

import com.fu1fan.smartpot.protocol.PlantDiary
import com.fu1fan.smartpot.protocol.RealtimeEvent
import com.fu1fan.smartpot.protocol.RealtimeEventType
import com.fu1fan.smartpot.server.appJson
import com.fu1fan.smartpot.server.store.SmartPotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.encodeToJsonElement
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

class DiaryService(
    private val store: SmartPotStore,
    private val ai: CloudAiService,
    private val realtime: RealtimeHub,
) {
    suspend fun generate(potId: String, date: LocalDate = LocalDate.now()): PlantDiary {
        store.listDiaries(potId).firstOrNull { it.diaryDate == date.toString() }?.let { return it }
        val pot = requireNotNull(store.findPot(potId))
        val content = decorateDiaryContent(ai.generateDiary(pot, date.toString()))
        val diary = PlantDiary(UUID.randomUUID().toString(), pot.id, date.toString(), "${pot.displayName}的日记", content, Instant.now().toString())
        if (store.saveDiary(diary)) realtime.publish(RealtimeEvent(RealtimeEventType.DIARY, pot.id, appJson.encodeToJsonElement(diary)))
        return store.listDiaries(potId).first { it.diaryDate == date.toString() }
    }

    fun start(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            store.listPots().forEach { pot ->
                val local = ZonedDateTime.now(java.time.ZoneId.of(pot.timezone))
                if (local.hour == 23 && local.minute >= 30) runCatching { generate(pot.id, local.toLocalDate()) }
            }
            delay(1.minutes)
        }
    }
}
