package com.anaalarm.ui.home

import com.anaalarm.alarm.AlarmScheduler
import com.anaalarm.alarm.AlarmScheduleResult
import com.anaalarm.data.AlarmEntity
import com.anaalarm.data.MemoryStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var memory: MemoryStore
    private lateinit var scheduler: AlarmScheduler
    private lateinit var vm: HomeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        memory = mockk(relaxed = true)
        scheduler = mockk(relaxed = true)
        everyAlarms()
        vm = HomeViewModel(memory, scheduler)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun everyAlarms() {
        every { memory.alarms } returns MutableStateFlow(emptyList())
    }

    @Test
    fun `deleteAlarm cancels scheduler then deletes from store`() = runTest {
        val alarm = AlarmEntity(id = 3, hour = 7, minute = 0, enabled = true)

        vm.deleteAlarm(alarm)
        advanceUntilIdle()

        verify { scheduler.cancel(alarm) }
        coVerify { memory.deleteAlarm(3) }
    }

    @Test
    fun `toggleAlarm off cancels pending alarm`() = runTest {
        val alarm = AlarmEntity(id = 5, hour = 8, minute = 15, enabled = true)
        coEvery { memory.getAlarm(5) } returns alarm.copy(enabled = false)

        vm.toggleAlarm(alarm)
        advanceUntilIdle()

        coVerify { memory.setAlarmEnabled(5, false) }
        verify { scheduler.cancel(any()) }
        verify(exactly = 0) { scheduler.schedule(any()) }
    }

    @Test
    fun `toggleAlarm on schedules alarm`() = runTest {
        val alarm = AlarmEntity(id = 5, hour = 8, minute = 15, enabled = false)
        val enabled = alarm.copy(enabled = true)
        coEvery { memory.getAlarm(5) } returns enabled
        every { scheduler.schedule(enabled) } returns AlarmScheduleResult.Scheduled(
            LocalDateTime.of(2026, 8, 3, 8, 15),
            1L
        )

        vm.toggleAlarm(alarm)
        advanceUntilIdle()

        coVerify { memory.setAlarmEnabled(5, true) }
        verify { scheduler.schedule(enabled) }
    }

    @Test
    fun `toggleAlarm rolls back enabled state when system scheduling fails`() = runTest {
        val alarm = AlarmEntity(id = 7, hour = 9, minute = 0, enabled = false)
        val enabled = alarm.copy(enabled = true)
        coEvery { memory.getAlarm(7) } returns enabled
        every { scheduler.schedule(enabled) } returns AlarmScheduleResult.Failed(
            AlarmScheduleResult.Failed.Reason.EXACT_ALARM_PERMISSION_REQUIRED
        )

        vm.toggleAlarm(alarm)
        advanceUntilIdle()

        coVerify { memory.setAlarmEnabled(7, true) }
        coVerify { memory.setAlarmEnabled(7, false) }
        verify { scheduler.cancel(enabled) }
    }
}
