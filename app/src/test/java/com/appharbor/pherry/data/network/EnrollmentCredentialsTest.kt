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
    @Test fun onlyCredentialsTheReceiverAcceptsAreReused() {
        val pairingCode = "123456789abc"
        assertNotEquals(pairingCode, EnrollmentCredentials.reuseOrCreate(pairingCode))
        val serverCredential = "a".repeat(64)
        assertEquals(serverCredential, EnrollmentCredentials.reuseOrCreate(serverCredential))
    }
}
