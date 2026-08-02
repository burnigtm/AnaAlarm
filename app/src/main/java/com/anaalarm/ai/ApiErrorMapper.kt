package com.anaalarm.ai

import retrofit2.HttpException
import java.io.IOException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

object ApiErrorMapper {

    fun fromThrowable(error: Throwable): ApiException {
        when (error) {
            is ApiException -> return error
            is HttpException -> {
                val code = error.code()
                if (code == 401 || code == 403) return ApiException("invalid api key")
                return ApiException("HTTP $code")
            }
            is SSLHandshakeException,
            is SSLException -> return ApiException("ssl error")
            is IOException -> {
                if (isCertificateTrustFailure(error)) return ApiException("ssl error")
                return ApiException("network error")
            }
            else -> {
                if (isCertificateTrustFailure(error)) return ApiException("ssl error")
                return ApiException(error.message ?: "request failed")
            }
        }
    }

    fun isCertificateTrustFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is CertPathValidatorException) return true
            val message = current.message.orEmpty()
            if (message.contains("Trust anchor for certification path not found", ignoreCase = true) ||
                message.contains("CertPathValidatorException", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }
}
