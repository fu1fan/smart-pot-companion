package com.fu1fan.smartpot.server.service

import com.fu1fan.smartpot.protocol.*
import com.fu1fan.smartpot.server.AppConfig
import com.fu1fan.smartpot.server.store.SmartPotStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.copyTo
import java.time.Instant
import java.util.UUID

class CloudAiService(
    private val config: AppConfig,
    private val store: SmartPotStore,
) : AutoCloseable {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(com.fu1fan.smartpot.server.appJson) }
    }

    suspend fun chat(pot: PotProfile, text: String, source: String): ChatResponse {
        require(text.isNotBlank() && text.length <= 1_000) { "对话内容应为 1-1000 个字符" }
        val now = Instant.now().toString()
        val user = ChatMessage(UUID.randomUUID().toString(), pot.id, ChatRole.USER, text.trim(), now, source)
        store.saveMessage(user)
        val recent = store.listMessages(pot.id, 20)
        val memories = store.listMemories(pot.id).takeLast(12)
        val telemetry = store.latestTelemetry(pot.id)
        val system = buildSystemPrompt(pot, telemetry, memories)
        val responseText = complete(
            system,
            recent.map { AiMessage(it.role.name.lowercase(), it.content) },
        )
        val assistant = ChatMessage(UUID.randomUUID().toString(), pot.id, ChatRole.ASSISTANT, responseText, Instant.now().toString(), "AI")
        store.saveMessage(assistant)
        return ChatResponse(user, assistant)
    }

    suspend fun generateDiary(pot: PotProfile, date: String): String {
        val telemetry = store.telemetryHistory(pot.id, 1_440)
        val messages = store.listMessages(pot.id, 40)
        val care = store.listCareLogs(pot.id).filter { it.occurredAt.startsWith(date) }
        val averageSoil = telemetry.map { it.soilPercent }.average().takeIf { !it.isNaN() }?.toInt()
        val averageLux = telemetry.map { it.lightLux }.average().takeIf { !it.isNaN() }?.toInt()
        val prompt = buildString {
            append("请用第一人称写一篇80到140字的中文盆栽日记，语气温暖自然，不虚构未提供的事件。")
            append("植物是${pot.species.chineseName}，名字是${pot.displayName}，日期$date。")
            append("平均土壤湿度${averageSoil ?: "未知"}%，平均光照${averageLux ?: "未知"}lux。")
            if (care.isNotEmpty()) append("养护事件：${care.joinToString { it.type.name }}。")
            if (messages.isNotEmpty()) append("今日对话摘要素材：${messages.takeLast(8).joinToString { it.content.take(80) }}。")
        }
        return complete("你是名叫小麦的拟人化智能盆栽。只输出日记正文。", listOf(AiMessage("user", prompt)))
    }

    suspend fun proxyOpenAi(request: JsonObject, output: ByteWriteChannel, pot: PotProfile? = null) {
        val key = requireNotNull(config.deepSeekApiKey?.takeIf { it.isNotBlank() }) { "云端模型密钥尚未配置" }
        val history = pot?.let { store.listMessages(it.id, 12) }.orEmpty()
        val memories = pot?.let { store.listMemories(it.id).takeLast(20) }.orEmpty()
        val withHistory = injectServerChatHistory(request, history, memories)
        val normalized = JsonObject(withHistory + ("model" to JsonPrimitive(config.deepSeekModel)))
        client.preparePost(config.deepSeekEndpoint) {
            bearerAuth(key)
            contentType(ContentType.Application.Json)
            setBody(normalized)
        }.execute { response ->
            require(response.status.value in 200..299) { "模型服务返回 ${response.status.value}" }
            response.bodyAsChannel().copyTo(output)
        }
    }

    private suspend fun complete(system: String, messages: List<AiMessage>): String {
        val key = config.deepSeekApiKey
        if (key.isNullOrBlank()) {
            return "我已经记住这件事啦。现在云端对话密钥还没有配置，等主人配置好后，我就能给出更完整的回答。"
        }
        val response = client.post(config.deepSeekEndpoint) {
            bearerAuth(key)
            contentType(ContentType.Application.Json)
            setBody(AiRequest(config.deepSeekModel, listOf(AiMessage("system", system)) + messages, stream = false))
        }.body<AiResponse>()
        return response.choices.firstOrNull()?.message?.content?.trim().orEmpty().ifBlank { "我刚才没有想好，可以再问我一次吗？" }
    }

    private fun buildSystemPrompt(pot: PotProfile, telemetry: DeviceTelemetry?, memories: List<UserMemory>) = buildString {
        append("你是智能盆栽小麦，当前植物为${pot.species.chineseName}（${pot.species.scientificName}）。")
        append("养护知识：${pot.species.knowledge}")
        append("适宜土壤湿度${pot.species.thresholds.soilMinPercent}-${pot.species.thresholds.soilMaxPercent}%，")
        append("适宜光照${pot.species.thresholds.lightMinLux}-${pot.species.thresholds.lightMaxLux}lux。")
        telemetry?.let { append("当前湿度${it.soilPercent}%，光照${it.lightLux}lux，状态${it.mood}。") }
        if (memories.isNotEmpty()) append("主人希望你记住：${memories.joinToString { it.content }}。")
        append("回答应简洁、友好、针对当前植物；不要将建议表述为专业医疗或农药安全保证。")
    }

    override fun close() = client.close()
}

internal fun injectServerChatHistory(
    request: JsonObject,
    history: List<ChatMessage>,
    memories: List<UserMemory> = emptyList(),
): JsonObject {
    if (history.isEmpty() && memories.isEmpty()) return request
    val incoming = request["messages"] as? JsonArray ?: return request
    val currentUser = incoming.lastOrNull { message ->
        message.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "user"
    } ?: return request
    val systemMessages = incoming.filter { message ->
        message.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "system"
    }
    val merged = buildJsonArray {
        systemMessages.forEach(::add)
        if (memories.isNotEmpty()) {
            add(buildJsonObject {
                put("role", JsonPrimitive("system"))
                put(
                    "content",
                    JsonPrimitive("主人明确要求你长期记住以下信息，并在相关问题中自然使用：${memories.joinToString("；") { it.content }}"),
                )
            })
        }
        history.takeLast(12).forEach { message ->
            add(buildJsonObject {
                put("role", JsonPrimitive(message.role.name.lowercase()))
                put("content", JsonPrimitive(message.content))
            })
        }
        add(currentUser)
    }
    return JsonObject(request + ("messages" to merged))
}

@Serializable
private data class AiRequest(val model: String, val messages: List<AiMessage>, val stream: Boolean)

@Serializable
private data class AiMessage(val role: String, val content: String)

@Serializable
private data class AiChoice(val message: AiMessage)

@Serializable
private data class AiResponse(val choices: List<AiChoice> = emptyList())
