package com.anaalarm.ui.home

import com.anaalarm.alarm.AlarmScheduler
import com.anaalarm.alarm.AlarmScheduleResult
import com.anaalarm.data.AlarmEntity
import com.anaalarm.data.AppSettings
import com.anaalarm.data.MemoryStore
import com.anaalarm.data.SettingsStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var memory: MemoryStore
    private lateinit var scheduler: AlarmScheduler
    private lateinit var settingsStore: SettingsStore
    private lateinit var vm: HomeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        memory = mockk(relaxed = true)
        scheduler = mockk(relaxed = true)
        settingsStore = mockk()
        every { settingsStore.settings } returns flowOf(AppSettings(habits = listOf("stretch")))
        coEvery { memory.todaySummary() } returns ""
        coEvery { memory.habitsForDate(any()) } returns emptyList()
        coEvery { memory.habitStreaks(any(), any()) } returns emptyMap()
        everyAlarms()
        vm = HomeViewModel(memory, scheduler, settingsStore)
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

        val collected = mutableListOf<HomeNotice>()
        val job = launch { vm.notices.collect { collected.add(it) } }

        vm.toggleAlarm(alarm)
        advanceUntilIdle()
        job.cancel()

        coVerify { memory.setAlarmEnabled(7, true) }
        coVerify { memory.setAlarmEnabled(7, false) }
        verify { scheduler.cancel(enabled) }
        assertEquals(
            listOf(
                HomeNotice.ScheduleFailed(
                    AlarmScheduleResult.Failed.Reason.EXACT_ALARM_PERMISSION_REQUIRED
                )
            ),
            collected
        )
    }

    @Test
    fun `upcoming picks the soonest enabled alarm`() {
        val now = LocalDateTime.of(2026, 8, 19, 10, 0)
        val laterToday = AlarmEntity(id = 1, hour = 18, minute = 0, enabled = true)
        val tomorrowMorning = AlarmEntity(id = 2, hour = 7, minute = 0, enabled = true)
        val disabledSoon = AlarmEntity(id = 3, hour = 10, minute = 5, enabled = false)

        val next = HomeViewModel.upcoming(listOf(laterToday, tomorrowMorning, disabledSoon), now)
        assertEquals(18, next!!.hour)
        assertEquals(0, next.minute)
        assertEquals(now.toLocalDate(), next.triggerAt.toLocalDate())
    }

    @Test
    fun `upcoming is null without alarms or when everything is disabled`() {
        val now = LocalDateTime.of(2026, 8, 19, 10, 0)

        assertEquals(null, HomeViewModel.upcoming(emptyList(), now))
        assertEquals(
            null,
            HomeViewModel.upcoming(
                listOf(AlarmEntity(id = 1, hour = 6, minute = 0, enabled = false)),
                now
            )
        )
    }

    @Test
    fun `buddy mirrors the avatar setting`() = runTest {
        every { settingsStore.settings } returns flowOf(AppSettings(avatar = "zebra"))
        vm = HomeViewModel(memory, scheduler, settingsStore)
        advanceUntilIdle()

        assertEquals("zebra", vm.buddy.value)
    }

    @Test
    fun `buddy falls back to the default for corrupt settings`() = runTest {
        every { settingsStore.settings } returns flowOf(AppSettings(avatar = "unicorn"))
        vm = HomeViewModel(memory, scheduler, settingsStore)
        advanceUntilIdle()

        assertEquals(com.anaalarm.ui.avatar.Avatars.DEFAULT, vm.buddy.value)
    }
}
