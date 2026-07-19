package com.fu1fan.smartpot.server.service

import com.fu1fan.smartpot.protocol.CreateDiaryRequest
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
    suspend fun saveManual(potId: String, request: CreateDiaryRequest, date: LocalDate): PlantDiary {
        val title = request.title.trim()
        val content = request.content.trim()
        require(title.isNotBlank() && title.length <= 60) { "日记标题应为 1-60 个字符" }
        require(content.isNotBlank() && content.length <= 1_000) { "日记内容应为 1-1000 个字符" }
        require(request.imageDataUrls.size <= 3) { "每篇日记最多添加 3 张图片" }
        require(request.imageDataUrls.all(::validDiaryImage)) { "日记图片格式或大小不正确" }
        require(request.moodEmoji == null || request.moodEmoji in setOf("😊", "🌱", "💧", "☀️", "🥰", "😴")) { "不支持的日记表情" }

        val existing = store.listDiaries(potId).firstOrNull { it.diaryDate == date.toString() }
        val diary = PlantDiary(
            id = existing?.id ?: UUID.randomUUID().toString(),
            potId = potId,
            diaryDate = date.toString(),
            title = title,
            content = content,
            createdAt = existing?.createdAt ?: Instant.now().toString(),
            imageDataUrls = request.imageDataUrls,
            moodEmoji = request.moodEmoji,
        )
        store.upsertDiary(diary)
        realtime.publish(RealtimeEvent(RealtimeEventType.DIARY, potId, appJson.encodeToJsonElement(diary)))
        return diary
    }

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

    private fun validDiaryImage(value: String): Boolean {
        if (value.length > 500_000) return false
        return value.startsWith("data:image/jpeg;base64,") ||
            value.startsWith("data:image/png;base64,") ||
            value.startsWith("data:image/webp;base64,")
    }
}
