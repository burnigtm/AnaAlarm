package com.anaalarm.support

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.anaalarm.debug.ScriptedRecognition
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

/** Persist evidence on every test and capture the visible UI on failures. */
class VoiceTestEvidence(private val additionalTrace: () -> List<String> = { emptyList() }) : TestWatcher() {
    private val directory get() = File(TestEnv.context.getExternalFilesDir(null), "test-evidence").apply { mkdirs() }
    override fun finished(description: Description) {
        File(directory, "${description.className}.${description.methodName}.trace.txt").writeText(
            (ScriptedRecognition.trace + additionalTrace()).sortedBy { it.substringBefore(':').toLongOrNull() ?: 0L }.joinToString("\n")
        )
    }
    override fun failed(e: Throwable, description: Description) {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .takeScreenshot(File(directory, "${description.className}.${description.methodName}.png"))
        File(directory, "${description.className}.${description.methodName}.failure.txt").writeText(e.stackTraceToString())
    }
}
