package com.fu1fan.smartpot.server

import kotlinx.serialization.json.Json

val appJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
