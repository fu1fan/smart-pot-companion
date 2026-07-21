package com.fu1fan.smartpot.server.service

import com.fu1fan.smartpot.protocol.CreateDiaryRequest
import com.fu1fan.smartpot.protocol.DiaryAuthor
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
    private val affinity: AffinityService,
) {
    suspend fun saveManual(potId: String, request: CreateDiaryRequest, date: LocalDate): PlantDiary {
        val title = request.title.trim()
        val content = request.content.trim()
        val authorName = request.authorName?.trim()?.takeIf(String::isNotBlank)
        require(title.isNotBlank() && title.length <= 60) { "日记标题应为 1-60 个字符" }
        require(content.isNotBlank() && content.length <= 1_000) { "日记内容应为 1-1000 个字符" }
        require(authorName == null || authorName.length <= 20) { "日记署名不能超过 20 个字符" }
        require(request.moodEmoji == null || request.moodEmoji in setOf("😊", "🌱", "💧", "☀️", "🥰", "😴")) { "不支持的日记表情" }
        require(request.imageDataUrls.size <= 3) { "每篇日记最多上传 3 张照片" }
        request.imageDataUrls.forEach { image ->
            require(image.startsWith("data:image/jpeg;base64,") || image.startsWith("data:image/png;base64,")) { "照片格式应为 JPEG 或 PNG" }
            require(image.length <= 1_500_000) { "单张照片不能超过约 1MB" }
        }

        val existing = store.listDiaries(potId).firstOrNull {
            it.diaryDate == date.toString() && it.author == DiaryAuthor.USER
        }
        val diary = PlantDiary(
            id = existing?.id ?: UUID.randomUUID().toString(),
            potId = potId,
            diaryDate = date.toString(),
            title = title,
            content = content,
            createdAt = existing?.createdAt ?: Instant.now().toString(),
            imageDataUrls = request.imageDataUrls,
            moodEmoji = request.moodEmoji,
            author = DiaryAuthor.USER,
            authorName = authorName,
        )
        store.upsertDiary(diary)
        affinity.award(potId, "diary:${date}:USER", 1)
        realtime.publish(RealtimeEvent(RealtimeEventType.DIARY, potId, appJson.encodeToJsonElement(diary)))
        return diary
    }

    suspend fun generate(potId: String, date: LocalDate = LocalDate.now()): PlantDiary {
        store.listDiaries(potId).firstOrNull {
            it.diaryDate == date.toString() && it.author == DiaryAuthor.WHEAT
        }?.let { return it }
        val pot = requireNotNull(store.findPot(potId))
        val content = decorateDiaryContent(ai.generateDiary(pot, date.toString()))
        val diary = PlantDiary(
            UUID.randomUUID().toString(),
            pot.id,
            date.toString(),
            "${pot.displayName}的日记",
            content,
            Instant.now().toString(),
            author = DiaryAuthor.WHEAT,
        )
        if (store.saveDiary(diary)) realtime.publish(RealtimeEvent(RealtimeEventType.DIARY, pot.id, appJson.encodeToJsonElement(diary)))
        return store.listDiaries(potId).first {
            it.diaryDate == date.toString() && it.author == DiaryAuthor.WHEAT
        }
    }

    suspend fun deleteManual(potId: String, diaryId: String): Boolean {
        val diary = store.listDiaries(potId).firstOrNull { it.id == diaryId } ?: return false
        require(diary.author == DiaryAuthor.USER) { "只能删除用户写的日记" }
        if (!store.deleteUserDiary(potId, diaryId)) return false
        affinity.revoke(potId, "diary:${diary.diaryDate}:USER")
        realtime.publish(RealtimeEvent(RealtimeEventType.DIARY, potId, appJson.encodeToJsonElement(diary)))
        return true
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
