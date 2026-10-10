package com.anaalarm.sentinel

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.anaalarm.AnaAlarmApp
import com.anaalarm.alarm.AlarmScheduler
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26, 31, 35])
class SentinelHealthTest {
    @Test fun passiveChecksWorkWithoutOpeningLockedCredentialStorage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = mockk<AnaAlarmApp>()
        every { app.packageName } returns context.packageName
        every { app.getSystemService(any<Class<*>>()) } answers { context.getSystemService(firstArg<Class<*>>()) }
        every { app.packageManager } returns context.packageManager
        every { app.checkPermission(any(), any(), any()) } answers {
            context.checkPermission(firstArg(), secondArg(), thirdArg())
        }
        every { app.alarmScheduler } returns mockk<AlarmScheduler>().also {
            every { it.canScheduleExact() } returns false
        }
        every { app.isUserUnlocked() } returns false
        val result = AndroidSentinelHealth(app).collect()
        assertEquals(HealthCheck.entries.size, result.results.size)
        assertEquals(HealthStatus.NEEDS_ATTENTION, result.results.first { it.check == HealthCheck.EXACT_ALARMS }.status)
        assertEquals(HealthDetail.LOCKED, result.results.first { it.check == HealthCheck.API_KEY }.detail)
        assertEquals(HealthDetail.LOCKED, result.results.first { it.check == HealthCheck.ENABLED_ALARMS }.detail)
        if (android.os.Build.VERSION.SDK_INT < 30) {
            assertEquals(HealthDetail.UNSUPPORTED, result.results.first { it.check == HealthCheck.PROCESS_EXITS }.detail)
        }
        verify(exactly = 0) { app.ensureCredentialStorage() }
        verify(exactly = 0) { app.settingsStore }
    }
}
