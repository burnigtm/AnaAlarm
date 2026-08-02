package com.anaalarm.ai

import com.anaalarm.BuildConfig
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Debug-only TLS helpers. Host antivirus HTTPS scanning (e.g. Avast) often MITMs
 * emulator traffic with a local CA that Android does not trust, which surfaces as
 * SSLHandshakeException and looks like a broken API key.
 */
internal object DebugTls {

    fun applyIfDebug(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        if (!BuildConfig.DEBUG) return builder
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        return builder
            .sslSocketFactory(context.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
    }
}
