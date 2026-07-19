package com.fu1fan.smartpot.server.service

import com.fu1fan.smartpot.protocol.CareWeather
import com.fu1fan.smartpot.server.appJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

class WeatherService : AutoCloseable {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(appJson) }
        install(HttpTimeout) {
            requestTimeoutMillis = 6_000
            connectTimeoutMillis = 4_000
        }
    }

    suspend fun current(
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        fallback: CareWeather,
    ): CareWeather = runCatching {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "定位坐标无效" }
        val response = client.get("https://api.open-meteo.com/v1/forecast") {
            parameter("latitude", latitude)
            parameter("longitude", longitude)
            parameter("current", "temperature_2m,relative_humidity_2m,weather_code,precipitation,cloud_cover")
            parameter("timezone", "auto")
            parameter("forecast_days", 1)
        }.body<OpenMeteoResponse>()
        val current = requireNotNull(response.current) { "实时天气暂不可用" }
        val condition = weatherCodeLabel(current.weatherCode)
        fallback.copy(
            date = date.toString(),
            condition = condition,
            hint = weatherCareHint(condition, current.temperatureC, current.relativeHumidityPercent),
            temperatureC = current.temperatureC,
            relativeHumidityPercent = current.relativeHumidityPercent,
            source = "OPEN_METEO",
        )
    }.getOrElse {
        fallback.copy(source = "DEVICE")
    }

    override fun close() = client.close()
}

internal fun weatherCodeLabel(code: Int): String = when (code) {
    0 -> "晴朗"
    1, 2 -> "多云"
    3 -> "阴天"
    45, 48 -> "有雾"
    in 51..57 -> "毛毛雨"
    in 61..67 -> "有雨"
    in 71..77 -> "有雪"
    in 80..82 -> "阵雨"
    in 85..86 -> "阵雪"
    in 95..99 -> "雷雨"
    else -> "天气变化"
}

private fun weatherCareHint(condition: String, temperatureC: Double, humidity: Int): String = when {
    condition.contains("雨") -> "室外有降水，注意通风并避免盆土积水"
    condition.contains("雪") -> "天气寒冷，注意远离冷风和低温窗边"
    temperatureC >= 32 -> "气温较高，留意盆土失水并避开强烈直射光"
    temperatureC <= 8 -> "气温较低，注意保温并减少冷风直吹"
    humidity >= 80 -> "空气湿度较高，保持周围通风"
    humidity <= 35 -> "空气偏干，可留意叶片和盆土状态"
    else -> "天气较平稳，继续结合盆栽传感器数据养护"
}

@Serializable
private data class OpenMeteoResponse(val current: OpenMeteoCurrent? = null)

@Serializable
private data class OpenMeteoCurrent(
    @SerialName("temperature_2m") val temperatureC: Double,
    @SerialName("relative_humidity_2m") val relativeHumidityPercent: Int,
    @SerialName("weather_code") val weatherCode: Int,
)
