package com.anaalarm.alarm

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {

    @Test
    fun `locked boot always selects device protected state`() {
        assertTrue(
            BootReceiver.shouldUseDirectBootState(
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
                userUnlocked = false
            )
        )
    }

    @Test
    fun `any reschedule broadcast stays device protected while user is locked`() {
        assertTrue(
            BootReceiver.shouldUseDirectBootState(
                Intent.ACTION_TIME_CHANGED,
                userUnlocked = false
            )
        )
    }

    @Test
    fun `unlocked boot reconciles credential state`() {
        assertFalse(
            BootReceiver.shouldUseDirectBootState(
                Intent.ACTION_BOOT_COMPLETED,
                userUnlocked = true
            )
        )
        assertFalse(
            BootReceiver.shouldUseDirectBootState(
                Intent.ACTION_USER_UNLOCKED,
                userUnlocked = true
            )
        )
    }
}
