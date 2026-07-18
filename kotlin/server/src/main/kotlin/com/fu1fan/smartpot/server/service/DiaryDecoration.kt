package com.fu1fan.smartpot.server.service

private val DiaryEmojis = listOf("🌱", "☀️", "💧", "🍃", "✨")

internal fun decorateDiaryContent(content: String): String {
    val trimmed = content.trim()
    if (trimmed.isBlank() || trimmed.any { Character.getType(it) == Character.SURROGATE.toInt() }) return trimmed
    val emoji = DiaryEmojis[kotlin.math.abs(trimmed.hashCode()) % DiaryEmojis.size]
    return "$emoji $trimmed"
}
