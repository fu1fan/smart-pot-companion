package com.fu1fan.smartpot.server

import com.fu1fan.smartpot.protocol.CreatePotRequest
import com.fu1fan.smartpot.protocol.CareDayOverview
import com.fu1fan.smartpot.protocol.CareType
import com.fu1fan.smartpot.protocol.ChatDaySummary
import com.fu1fan.smartpot.protocol.ChatMessage
import com.fu1fan.smartpot.protocol.ChatRole
import com.fu1fan.smartpot.protocol.CreateCareLogRequest
import com.fu1fan.smartpot.protocol.CreateDiaryRequest
import com.fu1fan.smartpot.protocol.CreateFocusSessionRequest
import com.fu1fan.smartpot.protocol.CreateMemoryRequest
import com.fu1fan.smartpot.protocol.CreateShareRequest
import com.fu1fan.smartpot.protocol.CreateScheduleItemRequest
import com.fu1fan.smartpot.protocol.DailyFocusSummary
import com.fu1fan.smartpot.protocol.DeviceScheduleItem
import com.fu1fan.smartpot.protocol.DeviceEvent
import com.fu1fan.smartpot.protocol.DeviceEventType
import com.fu1fan.smartpot.protocol.DeviceReportedState
import com.fu1fan.smartpot.protocol.DiaryAuthor
import com.fu1fan.smartpot.protocol.PlantDiary
import com.fu1fan.smartpot.protocol.PlantSpecies
import com.fu1fan.smartpot.protocol.PotProfile
import com.fu1fan.smartpot.protocol.RedeemShareRequest
import com.fu1fan.smartpot.protocol.ScheduleItem
import com.fu1fan.smartpot.protocol.ScheduleSyncState
import com.fu1fan.smartpot.protocol.ShareCode
import com.fu1fan.smartpot.protocol.ShareSession
import com.fu1fan.smartpot.protocol.UpdatePotRequest
import com.fu1fan.smartpot.protocol.UpdateScheduleItemRequest
import com.fu1fan.smartpot.protocol.UserMemory
import com.fu1fan.smartpot.server.catalog.SpeciesCatalog
import com.fu1fan.smartpot.server.service.conversationMessagesFromEvent
import com.fu1fan.smartpot.server.service.injectServerChatHistory
import com.fu1fan.smartpot.server.service.reportedProfileMatches
import com.fu1fan.smartpot.server.service.weatherCodeLabel
import com.fu1fan.smartpot.server.store.InMemorySmartPotStore
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

class ApplicationTest {
    private val config = AppConfig(
        port = 8080,
        demoToken = "test-owner-token",
        shareTokenSecret = "test-share-secret-with-enough-entropy",
        databaseUrl = null,
        databaseUser = "",
        databasePassword = "",
        mqttHost = "localhost",
        mqttPort = 1883,
        mqttUsername = null,
        mqttPassword = null,
        mqttTls = false,
        deepSeekEndpoint = "http://127.0.0.1:9/chat/completions",
        deepSeekApiKey = null,
        deepSeekModel = "test-model",
    )

    @Test
    fun `health and catalog are available`() = testApplication {
        application { module(config, InMemorySmartPotStore(), startMqtt = false) }
        val api = createClient { install(ContentNegotiation) { json(appJson) } }

        assertEquals(HttpStatusCode.OK, api.get("/health").status)
        val response = api.get("/api/v1/species")
        assertEquals(HttpStatusCode.OK, response.status)
        val species = response.body<List<PlantSpecies>>()
        assertEquals(50, species.size)
        val cactus = species.first { it.id == "cactus" }
        val basil = species.first { it.id == "basil" }
        assertEquals(1_000, cactus.thresholds.lightMinLux)
        assertEquals(10_000, cactus.thresholds.lightMaxLux)
        assertEquals(1_000, basil.thresholds.lightMinLux)
    }

    @Test
    fun `duplicate bearer header still authenticates owner routes`() = testApplication {
        application { module(config, InMemorySmartPotStore(), startMqtt = false) }
        val api = createClient { install(ContentNegotiation) { json(appJson) } }

        val response = api.get("/api/v1/pots") {
            headers {
                append(HttpHeaders.Authorization, "Bearer ${config.demoToken}")
                append(HttpHeaders.Authorization, "Bearer ${config.demoToken}")
            }
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, response.body<List<PotProfile>>().size)
    }

    @Test
    fun `owner can bind and share while guest cannot edit`() = testApplication {
        application { module(config, InMemorySmartPotStore(), startMqtt = false) }
        val api = createClient { install(ContentNegotiation) { json(appJson) } }

        val unauthorized = api.get("/api/v1/pots")
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)

        val created = api.post("/api/v1/pots") {
            bearerAuth(config.demoToken)
            contentType(ContentType.Application.Json)
            setBody(CreatePotRequest("esp32-test-001", "小绿", "pothos"))
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val pot = created.body<PotProfile>()

        val share = api.post("/api/v1/pots/${pot.id}/share") {
            bearerAuth(config.demoToken)
            contentType(ContentType.Application.Json)
            setBody(CreateShareRequest(30))
        }.body<ShareCode>()

        val session = api.post("/api/v1/share/redeem") {
            contentType(ContentType.Application.Json)
            setBody(RedeemShareRequest(share.code, "访客"))
        }.body<ShareSession>()
        assertEquals(pot.id, session.potId)

        assertEquals(HttpStatusCode.OK, api.get("/api/v1/pots/${pot.id}") { bearerAuth(session.token) }.status)
        val edit = api.patch("/api/v1/pots/${pot.id}") {
            bearerAuth(session.token)
            contentType(ContentType.Application.Json)
            setBody(UpdatePotRequest(displayName = "被修改"))
        }
        assertEquals(HttpStatusCode.BadRequest, edit.status)
        assertTrue(edit.body<String>().contains("仅主人"))
    }

    @Test
    fun `care overview includes new leaf and focus progress`() = testApplication {
        application { module(config, InMemorySmartPotStore(), startMqtt = false) }
        val api = createClient { install(ContentNegotiation) { json(appJson) } }

        val pot = api.post("/api/v1/pots") {
            bearerAuth(config.demoToken)
            contentType(ContentType.Application.Json)
            setBody(CreatePotRequest("esp32-focus-001", "小麦", "pothos"))
        }.body<PotProfile>()

        val care = api.post("/api/v1/pots/${pot.id}/care") {
            bearerAuth(config.demoToken)
            contentType(ContentType.Application.Json)
            setBody(CreateCareLogRequest(CareType.NEW_LEAF, note = "第一片新叶冒出来了"))
        }
        assertEquals(HttpStatusCode.Created, care.status)

        repeat(2) {
            val focus = api.post("/api/v1/pots/${pot.id}/focus/sessions") {
                bearerAuth(config.demoToken)
                contentType(ContentType.Application.Json)
                setBody(CreateFocusSessionRequest(minutes = 25, source = "APP"))
            }
            assertEquals(HttpStatusCode.Created, focus.status)
        }

        val overview = api.get("/api/v1/pots/${pot.id}/care-overview") { bearerAuth(config.demoToken) }.body<CareDayOverview>()
        assertEquals(2, overview.focus.pomodoroCount)
        assertEquals(50, overview.focus.focusMinutes)
        assertEquals(50, overview.focus.scheduleCompletionPercent)

        val summaries = api.get("/api/v1/pots/${pot.id}/focus/daily?days=5") { bearerAuth(config.demoToken) }.body<List<DailyFocusSummary>>()
        assertEquals(5, summaries.size)
        assertEquals(2, summaries.last().pomodoroCount)
    }

    @Test
    fun `owner can delete a saved memory`() = testApplication {
        application { module(config, InMemorySmartPotStore(), startMqtt = false) }
        val api = createClient { install(ContentNegotiation) { json(appJson) } }
        val pot = api.post("/api/v1/pots") {
            bearerAuth(config.demoToken)
            contentType(ContentType.Application.Json)
            setBody(CreatePotRequest("esp32-memory-001", "小麦", "pothos"))
        }.body<PotProfile>()
        val memory = api.post("/api/v1/pots/${pot.id}/memories") {
            bearerAuth(config.demoToken)
            contentType(ContentType.Application.Json)
            setBody(CreateMemoryRequest("记住我的生日"))
        }.body<com.fu1fan.smartpot.protocol.UserMemory>()

        val deleted = api.delete("/api/v1/pots/${pot.id}/memories/${memory.id}") { bearerAuth(config.demoToken) }

        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertTrue(api.get("/api/v1/pots/${pot.id}/memories") { bearerAuth(config.demoToken) }.body<List<com.fu1fan.smartpot.protocol.UserMemory>>().isEmpty())
    }

    @Test
    fun `user diary is separate from wheat diary and has no images`() = testApplication {
        val store = InMemorySmartPotStore()
        application { module(config, store, startMqtt = false) }
        val api = createClient { install(ContentNegotiation) { json(appJson) } }
        val pot = api.post("/api/v1/pots") {
            bearerAuth(config.demoToken)
            contentType(ContentType.Application.Json)
            setBody(CreatePotRequest("esp32-diary-001", "小麦", "pothos"))
        }.body<PotProfile>()
        store.saveDiary(
            PlantDiary(
                id = java.util.UUID.randomUUID().toString(),
                potId = pot.id,
                diaryDate = java.time.LocalDate.now(ZoneId.of(pot.timezone)).toString(),
                title = "小麦的一天",
                content = "今天晒到了暖暖的太阳。",
                createdAt = Instant.now().toString(),
                author = DiaryAuthor.WHEAT,
            ),
        )
        val ignoredImage = "data:image/jpeg;base64,AQID"

        val created = api.post("/api/v1/pots/${pot.id}/diaries") {
            bearerAuth(config.demoToken)
            contentType(ContentType.Application.Json)
            setBody(CreateDiaryRequest("今天的新叶", "小麦长出了一片新叶。", listOf(ignoredImage), "🌱"))
        }.body<com.fu1fan.smartpot.protocol.PlantDiary>()
        val updated = api.post("/api/v1/pots/${pot.id}/diaries") {
            bearerAuth(config.demoToken)
            contentType(ContentType.Application.Json)
            setBody(CreateDiaryRequest("今天的新叶", "叶片已经完全展开。", listOf(ignoredImage), "😊"))
        }.body<com.fu1fan.smartpot.protocol.PlantDiary>()

        assertEquals(created.id, updated.id)
        assertEquals("叶片已经完全展开。", updated.content)
        assertEquals(DiaryAuthor.USER, updated.author)
        assertTrue(updated.imageDataUrls.isEmpty())
        val diaries = api.get("/api/v1/pots/${pot.id}/diaries") { bearerAuth(config.demoToken) }.body<List<PlantDiary>>()
        assertEquals(2, diaries.size)
        assertEquals(setOf(DiaryAuthor.WHEAT, DiaryAuthor.USER), diaries.map(PlantDiary::author).toSet())
    }

    @Test
    fun `owner can create and complete schedule items`() = testApplication {
        application { module(config, InMemorySmartPotStore(), startMqtt = false) }
        val api = createClient { install(ContentNegotiation) { json(appJson) } }

        val pot = api.post("/api/v1/pots") {
            bearerAuth(config.demoToken)
            contentType(ContentType.Application.Json)
            setBody(CreatePotRequest("esp32-schedule-001", "小麦", "pothos"))
        }.body<PotProfile>()

        val created = api.post("/api/v1/pots/${pot.id}/schedule") {
            bearerAuth(config.demoToken)
            contentType(ContentType.Application.Json)
            setBody(CreateScheduleItemRequest(title = "浇水", displayTime = "今晚 8 点"))
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val item = created.body<ScheduleItem>()

        val list = api.get("/api/v1/pots/${pot.id}/schedule") { bearerAuth(config.demoToken) }.body<ScheduleSyncState>()
        assertEquals(1, list.items.size)
        assertEquals("浇水", list.items.first().title)

        val completed = api.patch("/api/v1/pots/${pot.id}/schedule/${item.id}") {
            bearerAuth(config.demoToken)
            contentType(ContentType.Application.Json)
            setBody(UpdateScheduleItemRequest(completed = true))
        }.body<ScheduleItem>()
        assertTrue(completed.completed)

        val overview = api.get("/api/v1/pots/${pot.id}/care-overview") { bearerAuth(config.demoToken) }.body<CareDayOverview>()
        assertEquals(100, overview.focus.scheduleCompletionPercent)
    }

    @Test
    fun `device reported schedule items merge into shared schedule`() = runBlocking {
        val store = InMemorySmartPotStore()
        store.seedSpecies(SpeciesCatalog.all)
        val species = requireNotNull(store.findSpecies("pothos"))
        val pot = store.savePot(
            PotProfile(
                id = "11111111-1111-1111-1111-111111111111",
                deviceId = "esp32-reported-schedule",
                displayName = "小麦",
                species = species,
                createdAt = "2026-07-16T00:00:00Z",
            ),
        )

        val changed = mergeDeviceScheduleItems(
            store,
            pot,
            listOf(DeviceScheduleItem(title = "晒太阳", displayTime = "今天下午", completed = false)),
            Instant.parse("2026-07-18T10:00:00Z"),
        )

        assertTrue(changed)
        val items = store.listScheduleItems(pot.id)
        assertEquals(1, items.size)
        assertEquals("晒太阳", items.first().title)
        assertEquals("ESP", items.first().source)
        assertEquals(3, potGrowthDays(pot, Instant.parse("2026-07-18T10:00:00Z")))
    }

    @Test
    fun `schedule time completion and two minute visibility use one server model`() = runBlocking {
        val store = InMemorySmartPotStore()
        store.seedSpecies(SpeciesCatalog.all)
        val pot = PotProfile(
            id = "22222222-2222-2222-2222-222222222222",
            deviceId = "esp32-schedule-model",
            displayName = "小麦",
            species = requireNotNull(store.findSpecies("pothos")),
            timezone = "Asia/Shanghai",
            createdAt = "2026-07-18T00:00:00Z",
        )
        val dueAt = parseScheduleDueAt(pot, "07-18/23:20", Instant.parse("2026-07-18T10:00:00Z"))
        assertEquals(Instant.parse("2026-07-18T15:20:00Z"), dueAt)

        val completed = scheduleItemFrom(
            pot,
            CreateScheduleItemRequest("浇水", displayTime = "07-19/23:20"),
            "APP",
            Instant.parse("2026-07-18T10:00:00Z"),
        ).copy(
            completed = true,
            completedAt = "2026-07-18T10:01:00Z",
            updatedAt = "2026-07-18T10:01:00Z",
        )
        assertEquals(1, scheduleState(listOf(completed), Instant.parse("2026-07-18T10:02:59Z")).items.size)
        assertEquals(0, scheduleState(listOf(completed), Instant.parse("2026-07-18T10:03:01Z")).items.size)
        assertEquals(100, scheduleCompletionPercent(listOf(completed), java.time.LocalDate.parse("2026-07-18"), ZoneId.of("Asia/Shanghai")))
    }

    @Test
    fun `chat history is grouped by plant day and can be queried by date`() = testApplication {
        val store = InMemorySmartPotStore()
        application { module(config, store, startMqtt = false) }
        val api = createClient { install(ContentNegotiation) { json(appJson) } }
        val pot = api.post("/api/v1/pots") {
            bearerAuth(config.demoToken)
            contentType(ContentType.Application.Json)
            setBody(CreatePotRequest("esp32-chat-history", "小麦", "pothos"))
        }.body<PotProfile>()

        store.saveMessage(ChatMessage("33333333-3333-3333-3333-333333333331", pot.id, ChatRole.USER, "第一天", "2026-07-18T15:20:00Z", "ESP"))
        store.saveMessage(ChatMessage("33333333-3333-3333-3333-333333333332", pot.id, ChatRole.ASSISTANT, "我记得", "2026-07-18T15:20:01Z", "ESP"))
        store.saveMessage(ChatMessage("33333333-3333-3333-3333-333333333333", pot.id, ChatRole.USER, "第二天", "2026-07-19T01:00:00Z", "APP"))

        val days = api.get("/api/v1/pots/${pot.id}/chat/days") { bearerAuth(config.demoToken) }
            .body<List<ChatDaySummary>>()
        assertEquals(listOf("2026-07-19", "2026-07-18"), days.map(ChatDaySummary::date))
        assertEquals(2, days.last().messageCount)

        val firstDay = api.get("/api/v1/pots/${pot.id}/chat?date=2026-07-18") { bearerAuth(config.demoToken) }
            .body<List<ChatMessage>>()
        assertEquals(listOf("第一天", "我记得"), firstDay.map(ChatMessage::content))
    }

    @Test
    fun `ESP conversation event produces stable shared chat messages`() = runBlocking {
        val store = InMemorySmartPotStore()
        store.seedSpecies(SpeciesCatalog.all)
        val pot = PotProfile(
            id = "44444444-4444-4444-4444-444444444444",
            deviceId = "esp32-chat-event",
            displayName = "小麦",
            species = requireNotNull(store.findSpecies("pothos")),
            createdAt = "2026-07-18T00:00:00Z",
        )
        val event = DeviceEvent(
            eventId = "esp32-chat-event-42",
            deviceId = pot.deviceId,
            type = DeviceEventType.CONVERSATION,
            occurredAt = "2026-07-19T02:30:00Z",
            data = buildJsonObject {
                put("userText", "你好小麦")
                put("assistantText", "我在呢。")
            },
        )

        val first = requireNotNull(conversationMessagesFromEvent(pot, event))
        val replay = requireNotNull(conversationMessagesFromEvent(pot, event))
        assertEquals(first.userMessage.id, replay.userMessage.id)
        assertEquals("ESP", first.userMessage.source)
        assertEquals("我在呢。", first.assistantMessage.content)
    }

    @Test
    fun `device model request uses canonical server chat context`() {
        val request = buildJsonObject {
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", "你是小麦") })
                add(buildJsonObject { put("role", "user"); put("content", "仅在ESP本地的旧内容") })
                add(buildJsonObject { put("role", "user"); put("content", "现在的问题") })
            })
        }
        val history = listOf(
            ChatMessage("55555555-5555-5555-5555-555555555551", "pot", ChatRole.USER, "服务器记忆", "2026-07-19T00:00:00Z", "ESP"),
            ChatMessage("55555555-5555-5555-5555-555555555552", "pot", ChatRole.ASSISTANT, "已经记住", "2026-07-19T00:00:01Z", "ESP"),
        )
        val memories = listOf(
            UserMemory("55555555-5555-5555-5555-555555555553", "pot", "我的生日是8月12日", "2026-07-19T00:00:02Z"),
        )

        val merged = injectServerChatHistory(request, history, memories)["messages"] as JsonArray
        val contents = merged.map { it.jsonObject["content"]!!.jsonPrimitive.content }
        assertEquals(
            listOf(
                "你是小麦",
                "主人明确要求你长期记住以下信息，并在相关问题中自然使用：我的生日是8月12日",
                "服务器记忆",
                "已经记住",
                "现在的问题",
            ),
            contents,
        )
    }

    @Test
    fun `open meteo weather codes use readable chinese labels`() {
        assertEquals("晴朗", weatherCodeLabel(0))
        assertEquals("有雨", weatherCodeLabel(63))
        assertEquals("雷雨", weatherCodeLabel(95))
    }

    @Test
    fun `reported profile ignores unsupported temperature thresholds`() {
        val species = SpeciesCatalog.all.first { it.thresholds.temperatureMinC != null }
        val pot = PotProfile(
            id = "55555555-5555-5555-5555-555555555554",
            deviceId = "smartpot-profile-test",
            displayName = "测试盆栽",
            species = species,
            createdAt = java.time.Instant.now().toString(),
        )
        val reported = DeviceReportedState(
            deviceId = pot.deviceId,
            reportedAt = java.time.Instant.now().toString(),
            brightnessPercent = 70,
            volumePercent = 60,
            standby = false,
            growthDays = potGrowthDays(pot),
            thresholds = species.thresholds.copy(temperatureMinC = null, temperatureMaxC = null),
            firmwareVersion = "test",
        )

        assertTrue(reportedProfileMatches(pot, reported))
        assertTrue(!reportedProfileMatches(pot, reported.copy(thresholds = reported.thresholds?.copy(lightMinLux = 1))))
    }

    @Test
    fun `daily touch events remain countable after later refreshes`() = runBlocking {
        val store = InMemorySmartPotStore()
        val now = java.time.Instant.now()
        val since = now.minusSeconds(60).toString()
        assertTrue(store.addAffinityEvent("pot", "device-event:touch-1", 1, now.toString()))
        assertTrue(store.addAffinityEvent("pot", "device-event:touch-2", 1, now.plusSeconds(1).toString()))
        assertTrue(store.addAffinityEvent("pot", "care:water", 3, now.plusSeconds(2).toString()))

        assertEquals(2, store.countAffinityEvents("pot", "device-event:", since))
        assertEquals(2, store.countAffinityEvents("pot", "device-event:", since))
    }
}
