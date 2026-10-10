package com.anaalarm.ui.avatar

import android.content.Context
import com.anaalarm.R

/** String resource for the localized species display name. */
fun avatarNameRes(species: String): Int = when (species) {
    Avatars.DINO -> R.string.buddy_dino
    Avatars.ZEBRA -> R.string.buddy_zebra
    else -> R.string.buddy_cheetah
}

fun avatarDisplayName(context: Context, species: String): String =
    context.getString(avatarNameRes(species))

fun reactionDescription(context: Context, mood: AvatarMood): String = context.getString(when (mood) {
    AvatarMood.SLEEPY -> R.string.reaction_sleepy
    AvatarMood.NEUTRAL -> R.string.reaction_neutral
    AvatarMood.TALKING -> R.string.reaction_talking
    AvatarMood.LISTENING -> R.string.reaction_listening
    AvatarMood.THINKING -> R.string.reaction_thinking
    AvatarMood.HAPPY -> R.string.reaction_happy
    AvatarMood.SAD -> R.string.reaction_sad
    AvatarMood.ACKNOWLEDGING -> R.string.reaction_acknowledging
    AvatarMood.REASSURING -> R.string.reaction_reassuring
})

/** TalkBack label: generic role plus the species the user actually picked. */
fun buddyContentDescription(context: Context, species: String): String =
    context.getString(
        R.string.buddy_cd,
        context.getString(R.string.buddy_label),
        avatarDisplayName(context, species)
    )
