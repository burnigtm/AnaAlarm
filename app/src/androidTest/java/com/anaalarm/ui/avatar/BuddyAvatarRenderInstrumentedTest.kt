package com.anaalarm.ui.avatar

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anaalarm.ui.theme.AnaAlarmTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Renders the procedural buddy rig on a real device for every species × mood combination.
 * The Canvas draws dozens of paths per frame; a crash in any branch (ears, plates, mane,
 * tear lines, overlays) surfaces here as either an exception or a missing node after the
 * clock is advanced through full animation loops.
 */
@RunWith(AndroidJUnit4::class)
class BuddyAvatarRenderInstrumentedTest {

    companion object {
        private const val TAG = "buddy_under_test"

        /** Slightly more than one full 4-second master loop. */
        private const val LOOP_WINDOW_MS = 4200L
    }

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everySpeciesRendersEveryMoodWithoutCrashing() {
        compose.mainClock.autoAdvance = false
        // Snapshot state so each swap actually recomposes into the next combination.
        val species = androidx.compose.runtime.mutableStateOf(Avatars.CHEETAH)
        val mood = androidx.compose.runtime.mutableStateOf(AvatarMood.NEUTRAL)
        val dark = androidx.compose.runtime.mutableStateOf(true)

        compose.setContent {
            AnaAlarmTheme(darkTheme = dark.value) {
                AnimalAvatar(
                    species = species.value,
                    mood = mood.value,
                    modifier = Modifier.size(120.dp).testTag(TAG)
                )
            }
        }
        // Advance through several complete master-clock loops so every periodic sub-animation
        // (blink slots, mouth flaps, yawn window, jump apex, sparkle twinkle) executes at least
        // once per combination.
        repeat(3) { compose.mainClock.advanceTimeBy(LOOP_WINDOW_MS / 3) }
        compose.onNodeWithTag(TAG).assertExists()

        Avatars.ALL.forEach { s ->
            AvatarMood.entries.forEach { m ->
                compose.runOnIdle { species.value = s; mood.value = m }
                repeat(2) {
                    compose.mainClock.advanceTimeBy(LOOP_WINDOW_MS / 2 + 16)
                }
                compose.onNodeWithTag(TAG).assertExists()
            }
        }

        // The rig must survive the light palette too (blush/zzz/sparkle alphas differ).
        compose.runOnIdle { dark.value = false; species.value = Avatars.ZEBRA; mood.value = AvatarMood.HAPPY }
        repeat(2) { compose.mainClock.advanceTimeBy(LOOP_WINDOW_MS / 2 + 16) }
        compose.onNodeWithTag(TAG).assertExists()
    }

    @Test
    fun rapidMoodChurnBetweenRealStatesStaysStable() {
        compose.mainClock.autoAdvance = false
        val mood = androidx.compose.runtime.mutableStateOf(AvatarMood.SLEEPY)

        compose.setContent {
            AnaAlarmTheme(darkTheme = true) {
                AnimalAvatar(
                    species = Avatars.DINO,
                    mood = mood.value,
                    modifier = Modifier.size(112.dp).testTag(TAG)
                )
            }
        }
        // Mirrors a fast session: starting → speaking → listening → thinking → ended with
        // error dips, flipped far faster than any real session would.
        val churn = listOf(
            AvatarMood.SLEEPY, AvatarMood.TALKING, AvatarMood.LISTENING,
            AvatarMood.THINKING, AvatarMood.SAD, AvatarMood.HAPPY,
            AvatarMood.NEUTRAL, AvatarMood.SAD, AvatarMood.HAPPY
        )
        churn.forEachIndexed { index, next ->
            compose.runOnIdle { mood.value = next }
            compose.mainClock.advanceTimeBy(200L + index * 37L)
            compose.onNodeWithTag(TAG).assertExists()
        }
    }
}
