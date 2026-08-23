package com.anaalarm.debug

import androidx.compose.foundation.background
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anaalarm.R
import com.anaalarm.ui.avatar.AnimalAvatar
import com.anaalarm.ui.avatar.AvatarMood
import com.anaalarm.ui.avatar.Avatars
import com.anaalarm.ui.wakeup.SessionStatus
import com.anaalarm.ui.wakeup.avatarMoodFor

internal enum class DocsChallengeKind { MATH, MEMORY }

internal val DocsDefaultSpecies: String = Avatars.CHEETAH

@Composable
internal fun DocsWakeScene(
    status: SessionStatus,
    species: String,
    aiText: String,
    userText: String = "",
    showChallenge: DocsChallengeKind? = null
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 196.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
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
                    text = "07:00",
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(16.dp))
                DocsStatusPill(status)
            }

            AnimalAvatar(
                species = species,
                mood = when (showChallenge) {
                    DocsChallengeKind.MATH -> AvatarMood.THINKING
                    DocsChallengeKind.MEMORY -> AvatarMood.LISTENING
                    null -> avatarMoodFor(status)
                },
                contentDescription = context.getString(R.string.buddy_label),
                modifier = Modifier.size(112.dp)
            )

            Column(modifier = Modifier.fillMaxWidth()) {
                if (aiText.isNotBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Text(
                            text = aiText,
                            modifier = Modifier.padding(20.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                if (userText.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "${context.getString(R.string.you_said)}: $userText",
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        textAlign = TextAlign.Center
                    )
                }
                if (status == SessionStatus.LISTENING) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = {}) {
                        Text(context.getString(R.string.type_instead))
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(start = 24.dp, top = 12.dp, end = 24.dp, bottom = 24.dp)
        ) {
            OutlinedButton(
                onClick = {},
                modifier = Modifier
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
            Button(
                onClick = {},
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier
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

    when (showChallenge) {
        DocsChallengeKind.MATH -> DocsChallengeDialog(
            species = species,
            mood = AvatarMood.THINKING,
            body = {
                Text(context.getString(R.string.challenge_math_prompt))
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "17 + 8",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    label = { Text(context.getString(R.string.challenge_answer_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmLabel = context.getString(R.string.submit)
        )
        DocsChallengeKind.MEMORY -> DocsChallengeDialog(
            species = species,
            mood = AvatarMood.LISTENING,
            body = {
                Text(context.getString(R.string.challenge_memory_show))
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "4821",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 8.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            confirmLabel = context.getString(R.string.challenge_ready)
        )
        null -> Unit
    }
}

@Composable
private fun DocsChallengeDialog(
    species: String,
    mood: AvatarMood,
    body: @Composable () -> Unit,
    confirmLabel: String
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        title = { Text(context.getString(R.string.challenge_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AnimalAvatar(
                    species = species,
                    mood = mood,
                    contentDescription = context.getString(R.string.buddy_label),
                    modifier = Modifier.size(72.dp)
                )
                Spacer(Modifier.height(12.dp))
                body()
            }
        },
        confirmButton = {
            Button(onClick = {}) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = {}) {
                Text(context.getString(R.string.cancel))
            }
        }
    )
}

@Composable
private fun DocsStatusPill(status: SessionStatus) {
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
