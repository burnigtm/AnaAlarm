package com.anaalarm.ui.wakeup

import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anaalarm.R
import com.anaalarm.alarm.ClockFormat
import com.anaalarm.ui.avatar.AnimalAvatar
import com.anaalarm.ui.avatar.AvatarMood
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.LocalTime

internal const val WAKE_SNOOZE_TEST_TAG = "wake_snooze_action"
internal const val WAKE_STOP_TEST_TAG = "wake_stop_action"
internal const val WAKE_BUDDY_TEST_TAG = "wake_buddy"
internal const val CHALLENGE_BUDDY_TEST_TAG = "challenge_buddy"

/** Maps live session state to what the buddy's face and body should be doing. */
internal fun avatarMoodFor(status: SessionStatus): AvatarMood = when (status) {
    SessionStatus.STARTING -> AvatarMood.SLEEPY
    SessionStatus.SPEAKING -> AvatarMood.TALKING
    SessionStatus.LISTENING -> AvatarMood.LISTENING
    SessionStatus.THINKING -> AvatarMood.THINKING
    SessionStatus.ENDED -> AvatarMood.HAPPY
}

/** Errors always win: a failed turn looks sad even if the session already ended. */
internal fun avatarMood(status: SessionStatus, hasError: Boolean): AvatarMood =
    if (hasError) AvatarMood.SAD else avatarMoodFor(status)

/** Speaking, listening, and thinking are the phases that pulse and tick. */
internal fun wakePhasePulses(status: SessionStatus): Boolean = when (status) {
    SessionStatus.SPEAKING, SessionStatus.LISTENING, SessionStatus.THINKING -> true
    SessionStatus.STARTING, SessionStatus.ENDED -> false
}

private fun avatarMood(controller: SessionController): AvatarMood =
    avatarMood(controller.status, controller.errorText != null)

@Composable
fun WakeUpScreen(controller: SessionController) {
    val context = LocalContext.current
    val view = LocalView.current
    var now by remember { mutableStateOf(LocalTime.now()) }
    var typed by remember { mutableStateOf("") }
    var listenSeconds by remember { mutableStateOf<Int?>(null) }
    val actionAreaBottomPadding = if (controller.snoozeAvailable) 196.dp else 116.dp
    val use24Hour = DateFormat.is24HourFormat(context)

    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(1000)
        }
    }

    var previousPhase by remember { mutableStateOf<SessionStatus?>(null) }
    LaunchedEffect(controller.status) {
        val prior = previousPhase
        previousPhase = controller.status
        if (prior != null && prior != controller.status && wakePhasePulses(controller.status)) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    LaunchedEffect(controller.status, controller.listenDeadlineElapsedRealtime) {
        val deadline = controller.listenDeadlineElapsedRealtime
        if (controller.status != SessionStatus.LISTENING || deadline == null) {
            listenSeconds = null
            return@LaunchedEffect
        }
        while (isActive) {
            val left = listenSecondsRemaining(deadline, SystemClock.elapsedRealtime())
            listenSeconds = left
            if (left == 0) break
            delay(200)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // Keep conversation/error content scrollable without allowing it to push the
                // safety-critical alarm actions below the viewport.
                .padding(
                    start = 24.dp,
                    top = 24.dp,
                    end = 24.dp,
                    bottom = actionAreaBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            // Keep state and error feedback in the initial viewport. SpaceBetween pushed the
            // second column below the pinned action panel on real API 26/36 emulator layouts.
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = context.getString(R.string.wake_up_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = ClockFormat.formatTime(now.hour, now.minute, use24Hour),
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(16.dp))
                WakePhaseHeader(
                    controller = controller,
                    listenSeconds = listenSeconds
                )
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                if (controller.aiText.isNotBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Text(
                            text = controller.aiText,
                            modifier = Modifier.padding(20.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                if (controller.lastUserText.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "${context.getString(R.string.you_said)}: ${controller.lastUserText}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }

                if (controller.partialUserText.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = controller.partialUserText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }

                controller.errorText?.let { error ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                controller.voiceHint?.let { hint ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (controller.textInputActive) {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            label = { Text(context.getString(R.string.type_fallback_hint)) },
                            singleLine = true,
                            enabled = controller.status == SessionStatus.LISTENING,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                // Keep the draft when the controller rejects input (for example
                                // mid-THINKING) so the user does not retype their answer.
                                if (controller.submitText(typed)) typed = ""
                            },
                            enabled = typed.isNotBlank() &&
                                controller.status == SessionStatus.LISTENING
                        ) {
                            Text(context.getString(R.string.send))
                        }
                    }
                } else if (controller.status == SessionStatus.LISTENING) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { controller.enableTextInput() }) {
                        Text(context.getString(R.string.type_instead))
                    }
                }

            }
        }

        // This panel is deliberately outside the scroll container. Stop must remain reachable
        // even when a long AI response, transcription, or error fills the conversation area.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(start = 24.dp, top = 12.dp, end = 24.dp, bottom = 24.dp)
        ) {
            if (controller.snoozeAvailable) {
                OutlinedButton(
                    onClick = { controller.snoozeNow() },
                    enabled = !controller.snoozeInFlight,
                    modifier = Modifier
                        .testTag(WAKE_SNOOZE_TEST_TAG)
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = context.getString(R.string.snooze_action),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            Button(
                onClick = { controller.requestStop() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier
                    .testTag(WAKE_STOP_TEST_TAG)
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Text(
                    text = context.getString(R.string.stop),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    controller.activeChallenge?.let { challenge ->
        StopChallengeDialog(controller = controller, challenge = challenge)
    }
}

@Composable
private fun StopChallengeDialog(
    controller: SessionController,
    challenge: StopChallengeUi
) {
    val context = LocalContext.current
    var typed by remember { mutableStateOf("") }
    var wrongAttempt by remember { mutableStateOf(false) }
    // Brief buddy reactions: sad on a wrong answer, happy on success, attentive while
    // the memory code is visible, otherwise pondering alongside the user.
    var reaction by remember { mutableStateOf<AvatarMood?>(null) }

    LaunchedEffect(reaction) {
        if (reaction != null) {
            delay(1300)
            reaction = null
        }
    }

    AlertDialog(
        onDismissRequest = { controller.dismissChallenge() },
        title = { Text(context.getString(R.string.challenge_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AnimalAvatar(
                    species = controller.buddy,
                    mood = reaction ?: if (
                        challenge.showingCode &&
                        challenge.type == com.anaalarm.alarm.DismissalChallenges.Type.MEMORY
                    ) {
                        AvatarMood.LISTENING
                    } else {
                        AvatarMood.THINKING
                    },
                    contentDescription = context.getString(R.string.buddy_label),
                    modifier = Modifier
                        .size(72.dp)
                        .testTag(CHALLENGE_BUDDY_TEST_TAG)
                )
                Spacer(Modifier.height(12.dp))
                when {
                    challenge.type == com.anaalarm.alarm.DismissalChallenges.Type.MEMORY &&
                        challenge.showingCode -> {
                        Text(context.getString(R.string.challenge_memory_show))
                        Spacer(Modifier.height(12.dp))
                        val code = remember(challenge) { controller.revealMemoryCodeForDisplay() }
                        Text(
                            text = code,
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 8.sp,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                    else -> {
                        if (challenge.type == com.anaalarm.alarm.DismissalChallenges.Type.MATH) {
                            Text(
                                text = context.getString(R.string.challenge_math_prompt),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = challenge.mathQuestion?.prompt.orEmpty(),
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                        } else {
                            Text(
                                text = context.getString(R.string.challenge_memory_prompt),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = typed,
                            onValueChange = {
                                typed = it
                                wrongAttempt = false
                            },
                            label = { Text(context.getString(R.string.challenge_answer_hint)) },
                            singleLine = true,
                            isError = wrongAttempt,
                            supportingText = {
                                if (wrongAttempt) {
                                    Text(context.getString(R.string.challenge_wrong))
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (challenge.showingCode &&
                        challenge.type == com.anaalarm.alarm.DismissalChallenges.Type.MEMORY
                    ) {
                        controller.beginChallengeAnswer()
                    } else {
                        if (!controller.submitChallengeAnswer(typed)) {
                            wrongAttempt = true
                            reaction = AvatarMood.SAD
                            typed = ""
                        } else {
                            reaction = AvatarMood.HAPPY
                        }
                    }
                },
                enabled = challenge.showingCode || typed.isNotBlank()
            ) {
                Text(
                    context.getString(
                        if (challenge.showingCode) R.string.challenge_ready else R.string.submit
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { controller.dismissChallenge() }) {
                Text(context.getString(R.string.cancel))
            }
        }
    )
}

@Composable
private fun WakePhaseHeader(controller: SessionController, listenSeconds: Int?) {
    val context = LocalContext.current
    val pulsing = wakePhasePulses(controller.status)
    val animationsOff = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }
    // Keep the infinite transition out of instrumented tests: a clock that never idles
    // stalls waitUntil after the first frame, same as the buddy's own animator gate.
    val scale = if (animationsOff) {
        1f
    } else {
        val ticking by rememberInfiniteTransition(label = "wakePhase").animateFloat(
            initialValue = 0.94f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700),
                repeatMode = RepeatMode.Reverse
            ),
            label = "wakePhaseScale"
        )
        if (pulsing) ticking else 1f
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        StatusPill(controller.status, scale)
        if (controller.status == SessionStatus.LISTENING && listenSeconds != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = context.getString(R.string.listen_countdown, listenSeconds),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("wake_listen_countdown")
            )
        }
        Spacer(Modifier.height(16.dp))
        // The wake-up buddy reacts live to every session state: sleepy while starting,
        // mouth flaps while Ana speaks, perked ears while listening, a thinking pose with
        // question bubble during model turns, and a celebration jump when the session ends.
        // Speaking, listening, and thinking also pulse so the phase change is visible.
        AnimalAvatar(
            species = controller.buddy,
            mood = avatarMood(controller),
            contentDescription = context.getString(R.string.buddy_label),
            modifier = Modifier
                .size(112.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .testTag(WAKE_BUDDY_TEST_TAG)
        )
    }
}

@Composable
private fun StatusPill(status: SessionStatus, pulseScale: Float) {
    val context = LocalContext.current
    val (label, color) = when (status) {
        SessionStatus.STARTING -> context.getString(R.string.status_starting) to Color(0xFFFFC24D)
        SessionStatus.SPEAKING -> context.getString(R.string.status_speaking) to Color(0xFF7BD88F)
        SessionStatus.LISTENING -> context.getString(R.string.status_listening) to Color(0xFF6FB7FF)
        SessionStatus.THINKING -> context.getString(R.string.status_thinking) to Color(0xFFB39DDB)
        SessionStatus.ENDED -> context.getString(R.string.status_ended) to Color(0xFF9E9E9E)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .graphicsLayer {
                    scaleX = pulseScale
                    scaleY = pulseScale
                }
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}
