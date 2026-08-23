package com.anaalarm.ui.avatar

import androidx.compose.ui.graphics.Color

/**
 * The wake-up buddy species. Values are persisted in DataStore as plain strings; unknown
 * stored values always fall back to the default so a future version or a damaged
 * preference can never crash rendering.
 */
object Avatars {
    const val CHEETAH = "cheetah"
    const val DINO = "dino"
    const val ZEBRA = "zebra"

    /** Display order everywhere (settings picker, previews). */
    val ALL = listOf(CHEETAH, DINO, ZEBRA)

    const val DEFAULT = CHEETAH

    /** Safe parse: anything unrecognized becomes the default species. */
    fun from(value: String?): String =
        if (value != null && value in ALL) value else DEFAULT
}

/** Emotional states the avatar rig can express. */
enum class AvatarMood {
    /** Waking up itself: droopy lids, slow yawn, drifting z's. */
    SLEEPY,
    /** Calm idle breathing with occasional blinks. */
    NEUTRAL,
    /** Ana is speaking: procedural mouth flaps, synced head bobs, raised brows. */
    TALKING,
    /** Microphone open: ears perk up, wide sparkly eyes, curious head tilt. */
    LISTENING,
    /** Model turn in flight: eyes look up-left, chin paw, floating question bubble. */
    THINKING,
    /** Celebration: bounce jump, closed happy eyes, sparkle burst. */
    HAPPY,
    /** Wrong answer / error: droop, sad brows, sweat drop. */
    SAD
}

/** Per-species palette consumed by [AnimalAvatar]. */
data class AvatarSpec(
    val id: String,
    /** Main fur/skin fill. */
    val body: Color,
    /** Darker shade of the body used for feet, shading and outlines. */
    val bodyShade: Color,
    /** Light belly patch. */
    val belly: Color,
    /** Spots (cheetah), back plates (dino), stripes + mane + hooves (zebra). */
    val detail: Color,
    /** Inner-ear fill. */
    val innerEar: Color,
    /** Muzzle patch fill (the dino has no muzzle patch and reuses the belly tone). */
    val muzzle: Color
)

private val CHEETAH_SPEC = AvatarSpec(
    id = Avatars.CHEETAH,
    body = Color(0xFFF4B95C),
    bodyShade = Color(0xFFD99A3E),
    belly = Color(0xFFFFF1D8),
    detail = Color(0xFF9C6416),
    innerEar = Color(0xFFF7A8A0),
    muzzle = Color(0xFFFFF1D8)
)

private val DINO_SPEC = AvatarSpec(
    id = Avatars.DINO,
    body = Color(0xFF8FCB72),
    bodyShade = Color(0xFF6FAE57),
    belly = Color(0xFFDCF3C8),
    detail = Color(0xFF5C9A47),
    innerEar = Color(0xFFF7A8A0),
    muzzle = Color(0xFFDCF3C8)
)

private val ZEBRA_SPEC = AvatarSpec(
    id = Avatars.ZEBRA,
    body = Color(0xFFF8F7F3),
    bodyShade = Color(0xFFC9C6BE),
    belly = Color(0xFFFFFFFF),
    detail = Color(0xFF37342F),
    innerEar = Color(0xFFBDB9AF),
    muzzle = Color(0xFF9C998F)
)

fun avatarSpec(id: String): AvatarSpec = when (id) {
    Avatars.DINO -> DINO_SPEC
    Avatars.ZEBRA -> ZEBRA_SPEC
    else -> CHEETAH_SPEC
}
