package com.anaalarm.support

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.anaalarm.debug.ScriptedRecognition
import com.anaalarm.debug.ScriptedTts
import org.junit.runner.Description
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import java.io.File

/** Runner-level evidence also covers existing tests that have no evidence rule. */
class TestEvidenceListener : RunListener() {
    private val starts = mutableMapOf<String, Long>()
    private fun name(test: Description) = "${test.className}.${test.methodName}"
    private fun directory() = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "test-evidence").apply { mkdirs() }
    override fun testStarted(description: Description) { starts[name(description)] = SystemClock.elapsedRealtime() }
    override fun testFailure(failure: Failure) {
        runCatching {
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(directory(), "${name(failure.description)}.png").outputStream().use {
                bitmap?.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap?.recycle()
            File(directory(), "${name(failure.description)}.failure.txt").writeText(failure.trace)
        }
    }
    override fun testFinished(description: Description) {
        runCatching {
            File(directory(), "${name(description)}.runner-trace.txt").writeText(
                "started_ms=${starts.remove(name(description))}\nfinished_ms=${SystemClock.elapsedRealtime()}\n" +
                    (ScriptedRecognition.trace + ScriptedTts.trace).sortedBy { it.substringBefore(':').toLongOrNull() ?: 0L }.joinToString("\n")
            )
        }
    }
}
