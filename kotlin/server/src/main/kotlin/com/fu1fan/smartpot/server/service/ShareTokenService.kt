package com.fu1fan.smartpot.server.service

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class AccessIdentity(val actorName: String, val allowedPotId: String? = null, val owner: Boolean = false)

class ShareTokenService(private val secret: String) {
    fun issue(potId: String, actorName: String, expiresAt: Instant): String {
        val payload = "$potId|${actorName.replace("|", "")}|${expiresAt.epochSecond}"
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        return "share.$encoded.${sign(encoded)}"
    }

    fun verify(token: String): AccessIdentity? {
        val parts = token.split('.')
        if (parts.size != 3 || parts[0] != "share" || !MessageDigest.isEqual(sign(parts[1]).toByteArray(), parts[2].toByteArray())) return null
        val payload = runCatching { String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8) }.getOrNull() ?: return null
        val values = payload.split('|')
        if (values.size != 3 || values[2].toLongOrNull()?.let { it <= Instant.now().epochSecond } != false) return null
        return AccessIdentity(values[1], values[0], owner = false)
    }

    private fun sign(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)))
    }
}
