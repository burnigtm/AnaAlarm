package com.anaalarm.ui.wakeup

import com.anaalarm.ui.avatar.AvatarMood
import org.junit.Assert.assertEquals
import org.junit.Test

/** Session status → buddy mood mapping must stay total and intentional. */
class AvatarMoodMappingTest {

    @Test
    fun `every session status maps to a distinct mood`() {
        val mappings = listOf(
            SessionStatus.STARTING to AvatarMood.SLEEPY,
            SessionStatus.SPEAKING to AvatarMood.TALKING,
            SessionStatus.LISTENING to AvatarMood.LISTENING,
            SessionStatus.THINKING to AvatarMood.THINKING,
            SessionStatus.ENDED to AvatarMood.HAPPY
        )
        mappings.forEach { (status, mood) -> assertEquals(mood, avatarMoodFor(status)) }
        // Exhaustiveness: the when() in avatarMoodFor must cover every status.
        assertEquals(SessionStatus.entries.size, mappings.size)
        assertEquals(mappings.size, mappings.map { it.second }.distinct().size)
    }

    @Test
    fun `ended sessions celebrate rather than sleep`() {
        assertEquals(AvatarMood.HAPPY, avatarMoodFor(SessionStatus.ENDED))
    }

    @Test
    fun `errors override every status to sad`() {
        SessionStatus.entries.forEach { status ->
            assertEquals(AvatarMood.SAD, avatarMood(status, hasError = true))
        }
    }

    @Test
    fun `without errors the status mapping is used`() {
        assertEquals(AvatarMood.TALKING, avatarMood(SessionStatus.SPEAKING, hasError = false))
        assertEquals(AvatarMood.HAPPY, avatarMood(SessionStatus.ENDED, hasError = false))
    }
}
