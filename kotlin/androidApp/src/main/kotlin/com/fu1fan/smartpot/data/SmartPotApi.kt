package com.fu1fan.smartpot.data

import com.fu1fan.smartpot.protocol.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

val mobileJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

class SmartPotApi(
    serverUrl: String,
    private val tokenProvider: () -> String,
) : AutoCloseable {
    private val base = serverUrl.trimEnd('/')
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(mobileJson) }
        install(WebSockets)
        expectSuccess = true
    }

    private fun HttpRequestBuilder.authorize() { bearerAuth(tokenProvider()) }
    private suspend inline fun <reified T> getApi(path: String): T = client.get("$base$path") { authorize() }.body()
    private suspend inline fun <reified Request, reified Response> postApi(path: String, request: Request): Response =
        client.post("$base$path") { authorize(); contentType(ContentType.Application.Json); setBody(request) }.body()
    private suspend inline fun <reified Request, reified Response> patchApi(path: String, request: Request): Response =
        client.patch("$base$path") { authorize(); contentType(ContentType.Application.Json); setBody(request) }.body()
    private suspend inline fun <reified Response> postEmpty(path: String): Response = client.post("$base$path") { authorize() }.body()

    suspend fun species(): List<PlantSpecies> = getApi("/api/v1/species")
    suspend fun pots(): List<PotProfile> = getApi("/api/v1/pots")
    suspend fun createPot(request: CreatePotRequest): PotProfile = postApi("/api/v1/pots", request)
    suspend fun updatePot(id: String, request: UpdatePotRequest): PotProfile = patchApi("/api/v1/pots/$id", request)
    suspend fun snapshot(id: String): PotSnapshot = getApi("/api/v1/pots/$id/snapshot")
    suspend fun telemetry(id: String): List<DeviceTelemetry> = getApi("/api/v1/pots/$id/telemetry?limit=240")
    suspend fun careLogs(id: String): List<CareLog> = getApi("/api/v1/pots/$id/care")
    suspend fun reminders(id: String): List<CareReminder> = getApi("/api/v1/pots/$id/reminders")
    suspend fun addCare(id: String, request: CreateCareLogRequest): CareLog = postApi("/api/v1/pots/$id/care", request)
    suspend fun careOverview(id: String): CareDayOverview = getApi("/api/v1/pots/$id/care-overview")
    suspend fun focusDaily(id: String): List<DailyFocusSummary> = getApi("/api/v1/pots/$id/focus/daily?days=5")
    suspend fun addFocusSession(id: String): FocusSession = postApi("/api/v1/pots/$id/focus/sessions", CreateFocusSessionRequest(minutes = 25, source = "APP"))
    suspend fun schedule(id: String): ScheduleSyncState = getApi("/api/v1/pots/$id/schedule")
    suspend fun addSchedule(id: String, request: CreateScheduleItemRequest): ScheduleItem = postApi("/api/v1/pots/$id/schedule", request)
    suspend fun updateSchedule(id: String, scheduleId: String, request: UpdateScheduleItemRequest): ScheduleItem =
        patchApi("/api/v1/pots/$id/schedule/$scheduleId", request)
    suspend fun memories(id: String): List<UserMemory> = getApi("/api/v1/pots/$id/memories")
    suspend fun addMemory(id: String, text: String): UserMemory = postApi("/api/v1/pots/$id/memories", CreateMemoryRequest(text))
    suspend fun chatDays(id: String): List<ChatDaySummary> = getApi("/api/v1/pots/$id/chat/days")
    suspend fun messages(id: String, date: String? = null): List<ChatMessage> =
        getApi("/api/v1/pots/$id/chat${date?.let { "?date=$it" }.orEmpty()}")
    suspend fun chat(id: String, text: String): ChatResponse = postApi("/api/v1/pots/$id/chat", ChatRequest(text))
    suspend fun diaries(id: String): List<PlantDiary> = getApi("/api/v1/pots/$id/diaries")
    suspend fun generateDiary(id: String): PlantDiary = postEmpty("/api/v1/pots/$id/diaries/generate")
    suspend fun control(id: String, request: DeviceControlRequest): CommandSubmission = postApi("/api/v1/pots/$id/control", request)
    suspend fun share(id: String): ShareCode = postApi("/api/v1/pots/$id/share", CreateShareRequest())
    suspend fun redeem(code: String, actor: String): ShareSession = client.post("$base/api/v1/share/redeem") {
        contentType(ContentType.Application.Json); setBody(RedeemShareRequest(code, actor))
    }.body()

    fun realtime(id: String): Flow<RealtimeEvent> = flow {
        val wsBase = base.replaceFirst("http://", "ws://").replaceFirst("https://", "wss://")
        client.webSocket("$wsBase/api/v1/pots/$id/realtime", request = { authorize() }) {
            for (frame in incoming) if (frame is Frame.Text) emit(mobileJson.decodeFromString(frame.readText()))
        }
    }

    override fun close() = client.close()
}
