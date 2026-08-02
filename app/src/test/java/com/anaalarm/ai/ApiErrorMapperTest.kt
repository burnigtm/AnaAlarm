package com.anaalarm.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException

class ApiErrorMapperTest {

    @Test
    fun `ssl handshake maps to ssl error`() {
        val mapped = ApiErrorMapper.fromThrowable(
            SSLHandshakeException("Trust anchor for certification path not found.")
        )
        assertEquals("ssl error", mapped.message)
    }

    @Test
    fun `cert path cause maps to ssl error`() {
        val nested = IOException(
            "HTTP FAILED",
            CertPathValidatorException("Trust anchor for certification path not found.")
        )
        assertTrue(ApiErrorMapper.isCertificateTrustFailure(nested))
        assertEquals("ssl error", ApiErrorMapper.fromThrowable(nested).message)
    }

    @Test
    fun `generic io maps to network error`() {
        assertEquals(
            "network error",
            ApiErrorMapper.fromThrowable(IOException("connection reset")).message
        )
    }

    @Test
    fun `maskKey reports only presence and length`() {
        val key = "sk-abcdefghijklmnopqrstuvwxyz"
        val masked = DeepSeekClient.maskKey(key)
        assertEquals("set(len=${key.length})", masked)
        assertTrue(!masked.contains("sk-"))
        assertTrue(!masked.contains("abcdefghijklmnopqrstuvwxyz"))
    }
}
