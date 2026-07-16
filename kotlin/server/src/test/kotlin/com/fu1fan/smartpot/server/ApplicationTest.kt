package com.fu1fan.smartpot.server

import com.fu1fan.smartpot.protocol.CreatePotRequest
import com.fu1fan.smartpot.protocol.CreateShareRequest
import com.fu1fan.smartpot.protocol.PlantSpecies
import com.fu1fan.smartpot.protocol.PotProfile
import com.fu1fan.smartpot.protocol.RedeemShareRequest
import com.fu1fan.smartpot.protocol.ShareCode
import com.fu1fan.smartpot.protocol.ShareSession
import com.fu1fan.smartpot.protocol.UpdatePotRequest
import com.fu1fan.smartpot.server.store.InMemorySmartPotStore
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
        val response = api.get("/api/v1/species") { bearerAuth(config.demoToken) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(50, response.body<List<PlantSpecies>>().size)
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
}
