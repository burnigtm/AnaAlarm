package com.anaalarm.ai

import okhttp3.OkHttpClient

/**
 * Compatibility hook retained for existing client construction.
 *
 * Debug and release builds deliberately use the platform trust store and hostname verifier.
 * Local HTTPS inspection must be configured with an explicit development CA instead of a
 * process-wide trust-all client, because the latter also exposes API credentials.
 */
internal object DebugTls {

    fun applyIfDebug(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder
}
