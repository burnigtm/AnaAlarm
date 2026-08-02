package com.anaalarm.support

import androidx.annotation.StringRes
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText

private const val DEFAULT_TIMEOUT_MS = 20_000L

/** Localized lookup so UI assertions survive a device-language change. */
fun str(@StringRes id: Int, vararg args: Any): String =
    if (args.isEmpty()) TestEnv.context.getString(id) else TestEnv.context.getString(id, *args)

fun ComposeTestRule.awaitText(
    text: String,
    substring: Boolean = false,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    waitUntil(timeoutMs) { countText(text, substring) > 0 }
}

fun ComposeTestRule.awaitTextGone(
    text: String,
    substring: Boolean = false,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    waitUntil(timeoutMs) { countText(text, substring) == 0 }
}

fun ComposeTestRule.awaitContentDescription(
    description: String,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    waitUntil(timeoutMs) { countContentDescription(description) > 0 }
}

fun ComposeTestRule.hasText(text: String, substring: Boolean = false): Boolean =
    countText(text, substring) > 0

/** True once an editable field is on screen — used for the wake-up typing fallback. */
fun ComposeTestRule.hasTextField(): Boolean =
    try {
        onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
    } catch (_: IllegalStateException) {
        false
    }

fun ComposeTestRule.awaitTextField(timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
    waitUntil(timeoutMs) { hasTextField() }
}

private fun ComposeTestRule.countText(text: String, substring: Boolean): Int =
    try {
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size
    } catch (_: IllegalStateException) {
        // Thrown while the composition is still settling between screens.
        0
    }

private fun ComposeTestRule.countContentDescription(description: String): Int =
    try {
        onAllNodesWithContentDescription(description).fetchSemanticsNodes().size
    } catch (_: IllegalStateException) {
        0
    }
