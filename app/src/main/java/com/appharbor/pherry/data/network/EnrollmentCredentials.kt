package com.appharbor.pherry.data.network

import java.security.SecureRandom
import java.util.Base64

internal object EnrollmentCredentials {
    fun reuseOrCreate(existing: String): String {
        if (existing.matches(Regex("[A-Za-z0-9_-]{43,128}"))) return existing
        val secret = ByteArray(32)
        SecureRandom().nextBytes(secret)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
    }
}
