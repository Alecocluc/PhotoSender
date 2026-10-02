package com.appharbor.pherry.data.network

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class EnrollmentCredentialsTest {
    @Test fun responseLossRetriesTheSamePersistedCredential() {
        val secret = EnrollmentCredentials.reuseOrCreate("")
        assertEquals(32, Base64.getUrlDecoder().decode(secret).size)
        assertEquals(secret, EnrollmentCredentials.reuseOrCreate(secret))
        assertNotEquals(secret, EnrollmentCredentials.reuseOrCreate(""))
    }
    @Test fun legacyShortPairingCodesAreNeverReusedAsDeviceSecrets() {
        val oldCode = "123456789abc"
        assertNotEquals(oldCode, EnrollmentCredentials.reuseOrCreate(oldCode))
        val previousServerCredential = "a".repeat(64)
        assertEquals(previousServerCredential, EnrollmentCredentials.reuseOrCreate(previousServerCredential))
    }
}
