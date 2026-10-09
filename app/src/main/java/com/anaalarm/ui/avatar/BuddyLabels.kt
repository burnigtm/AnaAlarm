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

/** TalkBack label: generic role plus the species the user actually picked. */
fun buddyContentDescription(context: Context, species: String): String =
    context.getString(
        R.string.buddy_cd,
        context.getString(R.string.buddy_label),
        avatarDisplayName(context, species)
    )
