package com.anaalarm.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException

class ApiErrorMapperTest {

    private fun httpException(code: Int): HttpException =
        HttpException(
            Response.error<Any>(code, "error body".toResponseBody("text/plain".toMediaType()))
        )

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
    fun `http 401 maps to invalid api key`() {
        assertEquals("invalid api key", ApiErrorMapper.fromThrowable(httpException(401)).message)
    }

    @Test
    fun `http 429 maps to a typed rate limited error`() {
        assertEquals("rate limited", ApiErrorMapper.fromThrowable(httpException(429)).message)
    }

    @Test
    fun `other http codes keep the generic form`() {
        assertEquals("HTTP 500", ApiErrorMapper.fromThrowable(httpException(500)).message)
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
