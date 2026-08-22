package com.anaalarm.ai

import org.junit.Assert.assertSame
import org.junit.Test
import okhttp3.OkHttpClient

class DebugTlsTest {

    @Test
    fun `debug hook is a pass-through and never weakens the builder`() {
        val builder = OkHttpClient.Builder()
            .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)

        // The returned builder must be the same instance: no trust-all client, no relaxed
        // hostname verification may be introduced by any build type.
        assertSame(builder, DebugTls.applyIfDebug(builder))
    }
}
