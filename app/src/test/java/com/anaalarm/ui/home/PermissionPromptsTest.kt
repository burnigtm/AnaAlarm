package com.anaalarm.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPromptsTest {

    @Test
    fun `mic card appears only after a denial`() {
        assertFalse(showMicRecoveryCard(permissionGranted = true, userDeniedRequest = true))
        assertFalse(showMicRecoveryCard(permissionGranted = false, userDeniedRequest = false))
        assertTrue(showMicRecoveryCard(permissionGranted = false, userDeniedRequest = true))
    }
}
