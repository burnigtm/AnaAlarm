package com.anaalarm.ui.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the animation contract of the buddy rig: each mood must carry its signature
 * overlays and motion ranges at any clock phase, so a refactor can never silently
 * drop the yawn, question bubble, sparkles, sweat drop or mouth flaps.
 */
class AvatarPoseTest {

    private val phases = listOf(0f, 0.13f, 0.25f, 0.5f, 0.71f, 0.9f)

    private fun assertAtAllPhases(mood: AvatarMood, check: (Pose) -> Unit) {
        phases.forEach { t ->
            val pose = avatarPoseFor(mood, t)
            check(pose)
            // Global sanity: eyes and mouth stay in drawable ranges at every phase.
            assertTrue("eyeOpen out of range for $mood@$t", pose.eyeOpen in 0.05f..1f)
            assertTrue("mouthOpen out of range for $mood@$t", pose.mouthOpen in 0f..1f)
            assertTrue("jumpY non-negative for $mood@$t", pose.jumpY >= 0f)
        }
    }

    @Test
    fun `sleepy drifts z's and never sparkles`() = assertAtAllPhases(AvatarMood.SLEEPY) { p ->
        assertTrue(p.showZzz)
        assertTrue(!p.showSparkles && !p.showQuestion && !p.showSweat)
        assertTrue(p.eyeOpen < 0.6f)
    }

    @Test
    fun `neutral is the calm baseline with no overlays`() = assertAtAllPhases(AvatarMood.NEUTRAL) { p ->
        assertTrue(!p.showZzz && !p.showQuestion && !p.showSparkles && !p.showSweat)
        assertTrue(!p.happyEyes && !p.sadBrows && !p.frown)
        assertEquals(ArmPose.DOWN, p.armPose)
    }

    @Test
    fun `talking flaps the mouth through every loop`() {
        var maxOpen = 0f
        for (i in 0 until 40) {
            val p = avatarPoseFor(AvatarMood.TALKING, i / 40f)
            maxOpen = maxOf(maxOpen, p.mouthOpen)
        }
        assertTrue("mouth never opened; max=$maxOpen", maxOpen > 0.7f)
        assertAtAllPhases(AvatarMood.TALKING) { p -> assertTrue(p.mouthOpen > 0.02f) }
    }

    @Test
    fun `listening perks ears and cups paws`() = assertAtAllPhases(AvatarMood.LISTENING) { p ->
        assertEquals(1f, p.earPerk)
        assertEquals(ArmPose.CUP, p.armPose)
        assertTrue(p.pupilScale > 1f)
    }

    @Test
    fun `thinking looks up with its question bubble and chin paw`() = assertAtAllPhases(AvatarMood.THINKING) { p ->
        assertTrue(p.showQuestion)
        assertEquals(ArmPose.CHIN, p.armPose)
        assertTrue(p.lookY < 0f)
        assertTrue(!p.showZzz && !p.showSparkles)
    }

    @Test
    fun `happy jumps with raised arms and sparkles`() {
        var maxJump = 0f
        for (i in 0 until 40) {
            val p = avatarPoseFor(AvatarMood.HAPPY, i / 40f)
            maxJump = maxOf(maxJump, p.jumpY)
        }
        assertTrue("never left the ground; max=$maxJump", maxJump > 2f)
        assertAtAllPhases(AvatarMood.HAPPY) { p ->
            assertTrue(p.showSparkles && p.happyEyes)
            assertEquals(ArmPose.RAISED, p.armPose)
        }
    }

    @Test
    fun `sad frowns sweats and droops`() = assertAtAllPhases(AvatarMood.SAD) { p ->
        assertTrue(p.frown && p.sadBrows && p.showSweat)
        assertTrue(p.eyeOpen < 1f)
        assertTrue(!p.happyEyes)
    }

    @Test
    fun `every mood produces a pose`() {
        AvatarMood.entries.forEach { mood ->
            val pose = avatarPoseFor(mood, 0.25f)
            // Touch several fields so the compiler keeps Pose exhaustive for new moods.
            assertEquals(mood != AvatarMood.HAPPY, !pose.showSparkles)
        }
    }
}
