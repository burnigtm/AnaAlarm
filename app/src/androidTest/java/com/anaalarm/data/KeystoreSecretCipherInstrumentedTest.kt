package com.anaalarm.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@MediumTest
class KeystoreSecretCipherInstrumentedTest {

    private val cipher = KeystoreSecretCipher("anaalarm.test.${UUID.randomUUID()}")

    @After
    fun tearDown() {
        cipher.resetKey()
    }

    @Test
    fun encryptedCredentialRoundTripsWithoutEmbeddingPlaintext() {
        val plainText = "sk-sensitive-instrumented-value"

        val encrypted = cipher.encrypt(plainText)

        assertFalse(encrypted.contains(plainText))
        assertEquals(plainText, cipher.decrypt(encrypted))
    }

    @Test
    fun malformedCiphertextFailsClosed() {
        assertNull(cipher.decrypt("not-a-supported-payload"))
        assertNull(cipher.decrypt("v1:broken:payload"))
    }

    @Test
    fun lostOrInvalidatedKeyFailsClosedAndAReplacementCanBeCreated() {
        val encryptedWithOldKey = cipher.encrypt("old-secret")

        cipher.resetKey()

        assertNull(cipher.decrypt(encryptedWithOldKey))
        val encryptedWithReplacement = cipher.encrypt("new-secret")
        assertEquals("new-secret", cipher.decrypt(encryptedWithReplacement))
    }
}
