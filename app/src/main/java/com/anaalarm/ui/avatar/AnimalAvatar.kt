package com.anaalarm.ui.avatar

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * Procedurally drawn, animated wake-up buddy. Everything renders on a Compose [Canvas] in a
 * normalized 100x100 coordinate space — no image assets, no animation libraries. One master
 * 4-second clock drives every sub-animation through integer frequency multiples so the loop
 * never visibly jumps.
 */
@Composable
fun AnimalAvatar(
    species: String,
    mood: AvatarMood,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val spec = remember(species) { avatarSpec(species) }
    val context = LocalContext.current
    val animationsOff = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }
    // Instrumented tests (and users who turn off animator duration) must not host an infinite
    // Compose transition: it never goes idle, so waitUntil/awaitText stall after the first frame.
    val clock = if (animationsOff) {
        0.25f
    } else {
        val ticking by rememberInfiniteTransition(label = "buddy").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = LOOP_MS, easing = LinearEasing)
            ),
            label = "buddyClock"
        )
        ticking
    }
    val canvasModifier = if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else {
        modifier
    }
    Canvas(modifier = canvasModifier) {
        drawBuddy(spec, mood, clock)
    }
}

// ---------------------------------------------------------------------------
// Palette shared by every species
// ---------------------------------------------------------------------------

private val INK = Color(0xFF3A2E24)
private val SHINE = Color.White
private val BLUSH = Color(0x66FF8FA3)
private val TONGUE = Color(0xFFE8837F)
private val SPARKLE_GOLD = Color(0xFFFFD54D)
private val SWEAT_BLUE = Color(0xFF8FD3FF)
private val BUBBLE_FILL = Color(0xFFF3EFFA)
private val BUBBLE_EDGE = Color(0xFFB9AED6)
private val QUESTION_COLOR = Color(0xFF7E57C2)
private val ZZZ_COLOR = Color(0xFF8A8178)

private const val LOOP_MS = 4000
private const val BLINK_FRACTION = 0.06f

// ---------------------------------------------------------------------------
// Pose: the full animated parameter set derived from mood + clock
// ---------------------------------------------------------------------------

internal enum class ArmPose { DOWN, RAISED, CHIN, CUP }

internal data class Pose(
    val breath: Float = 0f,
    val sway: Float = 0f,
    val tiltDeg: Float = 0f,
    val bobY: Float = 0f,
    val jumpY: Float = 0f,
    val eyeOpen: Float = 1f,
    val happyEyes: Boolean = false,
    val pupilScale: Float = 1f,
    val lookX: Float = 0f,
    val lookY: Float = 0f,
    val browRaiseL: Float = 0f,
    val browRaiseR: Float = 0f,
    val sadBrows: Boolean = false,
    val mouthOpen: Float = 0f,
    val frown: Boolean = false,
    val tailWag: Float = 0f,
    val earPerk: Float = 0f,
    val armPose: ArmPose = ArmPose.DOWN,
    val showZzz: Boolean = false,
    val showQuestion: Boolean = false,
    val showSparkles: Boolean = false,
    val showSweat: Boolean = false
)

private fun poseFor(mood: AvatarMood, t: Float): Pose = avatarPoseFor(mood, t)

/**
 * Pure mood→animation-parameter mapping, exposed for deterministic unit tests: every mood
 * must keep its signature overlays and motion ranges regardless of clock phase [t].
 */
internal fun avatarPoseFor(mood: AvatarMood, t: Float): Pose {
    val tau = (2.0 * PI).toFloat()
    fun dip(cyclesPerLoop: Int): Float {
        val slot = frac(t * cyclesPerLoop)
        return if (slot < BLINK_FRACTION) sin((slot / BLINK_FRACTION) * PI.toFloat()) else 0f
    }
    return when (mood) {
        AvatarMood.SLEEPY -> {
            val yawnP = frac(t)
            val yawn =
                if (yawnP in 0.55f..0.78f) sin(((yawnP - 0.55f) / 0.23f) * PI.toFloat()) else 0f
            Pose(
                breath = sin(tau * t),
                sway = sin(tau * t) * 0.25f,
                tiltDeg = 6f + sin(tau * t * 2f) * 2.5f,
                bobY = 0.6f + sin(tau * t * 2f) * 0.5f,
                eyeOpen = 0.30f + 0.10f * sin(tau * t * 2f),
                mouthOpen = yawn * 0.9f,
                tailWag = sin(tau * t) * 0.5f,
                showZzz = true
            )
        }
        AvatarMood.NEUTRAL -> Pose(
            breath = sin(tau * t * 2f),
            sway = sin(tau * t),
            tiltDeg = sin(tau * t * 2f) * 2f,
            eyeOpen = 1f - dip(2),
            tailWag = sin(tau * t * 3f)
        )
        AvatarMood.TALKING -> {
            val flap = abs(sin(tau * t * 7f))
            Pose(
                breath = sin(tau * t * 2f),
                sway = sin(tau * t) * 0.6f,
                tiltDeg = sin(tau * t * 7f) * 2.5f,
                bobY = flap * 0.8f,
                eyeOpen = 1f - dip(2),
                browRaiseL = 0.7f,
                browRaiseR = 0.7f,
                mouthOpen = 0.15f + flap * 0.85f,
                tailWag = sin(tau * t * 4f)
            )
        }
        AvatarMood.LISTENING -> Pose(
            breath = sin(tau * t * 2f),
            sway = sin(tau * t) * 0.35f,
            tiltDeg = 7f + sin(tau * t * 2f) * 3f,
            eyeOpen = 1f - dip(1) * 0.9f,
            pupilScale = 1.18f,
            browRaiseL = 0.9f,
            browRaiseR = 0.9f,
            earPerk = 1f,
            armPose = ArmPose.CUP,
            tailWag = sin(tau * t * 3f)
        )
        AvatarMood.THINKING -> Pose(
            breath = sin(tau * t * 2f),
            tiltDeg = -5f + sin(tau * t * 2f) * 1.5f,
            eyeOpen = 1f - dip(1) * 0.7f,
            lookX = -0.45f,
            lookY = -0.55f,
            browRaiseL = 0.2f,
            browRaiseR = 1.4f,
            mouthOpen = 0.12f,
            earPerk = 0.3f,
            armPose = ArmPose.CHIN,
            showQuestion = true,
            tailWag = sin(tau * t * 2f) * 0.3f
        )
        AvatarMood.HAPPY -> Pose(
            breath = sin(tau * t * 3f),
            sway = sin(tau * t * 3f) * 0.5f,
            tiltDeg = sin(tau * t * 6f) * 4f,
            jumpY = abs(sin(tau * t * 3f)) * 4.5f,
            happyEyes = true,
            mouthOpen = 0.65f,
            earPerk = 1f,
            armPose = ArmPose.RAISED,
            showSparkles = true,
            tailWag = sin(tau * t * 6f)
        )
        AvatarMood.SAD -> Pose(
            breath = sin(tau * t * 2f),
            tiltDeg = -6f,
            bobY = 0.8f,
            eyeOpen = 0.72f,
            sadBrows = true,
            frown = true,
            showSweat = true,
            earPerk = -0.6f,
            tailWag = sin(tau * t * 2f) * 0.2f
        )
    }
}

// ---------------------------------------------------------------------------
// Scene composition
// ---------------------------------------------------------------------------

private fun DrawScope.drawBuddy(spec: AvatarSpec, mood: AvatarMood, t: Float) {
    val u = size.minDimension / 100f
    val pose = poseFor(mood, t)

    // Ground shadow shrinks and fades while the buddy is airborne.
    drawOval(
        color = Color.Black.copy(alpha = 0.10f * (1f - pose.jumpY / 9f)),
        topLeft = Offset(26f * u, 92.5f * u),
        size = Size(48f * u, (4.5f + pose.jumpY * 0.35f) * u)
    )

    translate(top = -(pose.jumpY * u)) {
        rotate(degrees = pose.sway * 2f, pivot = Offset(50f * u, 95f * u)) {
            drawTail(spec, pose.tailWag, u)
            translate(top = pose.bobY * u) {
                scale(
                    scaleX = 1f,
                    scaleY = 1f + pose.breath * 0.02f,
                    pivot = Offset(50f * u, 91f * u)
                ) {
                    drawBodyAndFeet(spec, u)
                    drawArms(spec, pose.armPose, u)
                }
                drawHead(spec, pose, u)
            }
        }
    }

    if (pose.showZzz) drawZzz(u, t)
    if (pose.showQuestion) drawQuestionBubble(u, t)
    if (pose.showSparkles) drawSparkles(u, t)
}

// ---------------------------------------------------------------------------
// Body parts
// ---------------------------------------------------------------------------

private fun DrawScope.drawBodyAndFeet(spec: AvatarSpec, u: Float) {
    drawRoundRect(
        color = spec.bodyShade.copy(alpha = 0.45f),
        topLeft = Offset(29f * u, 54f * u),
        size = Size(42f * u, 37f * u),
        cornerRadius = CornerRadius(19f * u, 19f * u)
    )
    drawRoundRect(
        color = spec.body,
        topLeft = Offset(30f * u, 55f * u),
        size = Size(40f * u, 35f * u),
        cornerRadius = CornerRadius(18f * u, 18f * u)
    )
    drawOval(
        color = spec.belly,
        topLeft = Offset(37f * u, 65.5f * u),
        size = Size(26f * u, 23f * u)
    )
    val footColor = if (spec.id == Avatars.ZEBRA) spec.detail else spec.bodyShade
    drawOval(footColor, Offset(33.5f * u, 85.5f * u), Size(13f * u, 9f * u))
    drawOval(footColor, Offset(53.5f * u, 85.5f * u), Size(13f * u, 9f * u))
}

/** Capsule limb hanging down from shoulder pivot, rotated clockwise by [angleDeg]. */
private fun DrawScope.arm(px: Float, py: Float, angleDeg: Float, len: Float, u: Float, color: Color) {
    rotate(degrees = angleDeg, pivot = Offset(px * u, py * u)) {
        drawRoundRect(
            color = color,
            topLeft = Offset((px - 3.1f) * u, py * u),
            size = Size(6.2f * u, len * u),
            cornerRadius = CornerRadius(3.1f * u, 3.1f * u)
        )
    }
}

private fun DrawScope.drawArms(spec: AvatarSpec, pose: ArmPose, u: Float) {
    when (pose) {
        ArmPose.DOWN -> {
            arm(33f, 64f, -16f, 13f, u, spec.body)
            arm(67f, 64f, 16f, 13f, u, spec.body)
        }
        ArmPose.RAISED -> {
            arm(33f, 63f, 138f, 13f, u, spec.body)
            arm(67f, 63f, -138f, 13f, u, spec.body)
            drawCircle(spec.bodyShade.copy(alpha = 0.35f), 3.1f * u, Offset(22.5f * u, 52.5f * u))
            drawCircle(spec.bodyShade.copy(alpha = 0.35f), 3.1f * u, Offset(77.5f * u, 52.5f * u))
        }
        ArmPose.CUP -> {
            arm(32f, 63f, 122f, 12f, u, spec.body)
            arm(68f, 63f, -122f, 12f, u, spec.body)
            drawCircle(spec.innerEar, 2.6f * u, Offset(24.5f * u, 34.5f * u))
            drawCircle(spec.innerEar, 2.6f * u, Offset(75.5f * u, 34.5f * u))
        }
        ArmPose.CHIN -> {
            arm(33f, 64f, -16f, 13f, u, spec.body)
            arm(66f, 63f, -142f, 12.5f, u, spec.body)
            drawCircle(spec.belly, 3f * u, Offset(56f * u, 47.5f * u))
        }
    }
}

private fun DrawScope.drawTail(spec: AvatarSpec, wag: Float, u: Float) {
    val wagX = wag * 6f
    when (spec.id) {
        Avatars.DINO -> {
            val tipX = (10f + wagX * 0.5f) * u
            val path = Path().apply {
                moveTo(44f * u, 70f * u)
                cubicTo(30f * u, 72f * u, 22f * u, 80f * u, tipX, 92f * u)
                cubicTo(24f * u, 86f * u, 36f * u, 86f * u, 46f * u, 86f * u)
                close()
            }
            drawPath(path, spec.body)
            drawPath(path, spec.bodyShade.copy(alpha = 0.45f), style = Stroke(0.9f * u))
        }
        Avatars.ZEBRA -> {
            val path = Path().apply {
                moveTo(69f * u, 83f * u)
                quadraticBezierTo((82f + wagX * 0.7f) * u, 92f * u, (87f + wagX) * u, 84f * u)
            }
            drawPath(path, spec.bodyShade, style = Stroke(4f * u, cap = StrokeCap.Round))
            drawOval(
                spec.detail,
                topLeft = Offset((84f + wagX) * u, 76f * u),
                size = Size(6f * u, 10f * u)
            )
        }
        else -> {
            // Cheetah: long upward curl with rings.
            val path = Path().apply {
                moveTo(69f * u, 80f * u)
                cubicTo(
                    (84f + wagX * 0.4f) * u, 86f * u,
                    (93f + wagX) * u, 76f * u,
                    (90f + wagX) * u, 60f * u
                )
            }
            drawPath(path, spec.bodyShade, style = Stroke(6f * u, cap = StrokeCap.Round))
            drawCircle(spec.detail, 3.1f * u, Offset((91f + wagX) * u, 69f * u))
            drawCircle(spec.detail, 3.1f * u, Offset((90.5f + wagX) * u, 62.5f * u))
            drawCircle(spec.detail, 3.1f * u, Offset((88.5f + wagX) * u, 56.5f * u))
        }
    }
}

// ---------------------------------------------------------------------------
// Head group
// ---------------------------------------------------------------------------

private fun DrawScope.drawHead(spec: AvatarSpec, pose: Pose, u: Float) {
    rotate(degrees = pose.tiltDeg, pivot = Offset(50f * u, 58f * u)) {
        drawEarsBehind(spec, pose.earPerk, u)
        drawCircle(
            spec.bodyShade.copy(alpha = 0.4f),
            radius = 26.5f * u,
            center = Offset(50f * u, 36f * u)
        )
        drawCircle(spec.body, radius = 26f * u, center = Offset(50f * u, 36f * u))
        drawSpeciesMarks(spec, u)
        drawMuzzleNose(spec, u)
        drawEyes(pose, u)
        drawBrows(pose, u)
        drawBlush(u)
        drawMouth(pose, u)
        if (pose.showSweat) drawSweat(u)
    }
}

private fun DrawScope.drawEarsBehind(spec: AvatarSpec, perk: Float, u: Float) {
    val raise = perk * 2.2f * u
    when (spec.id) {
        Avatars.DINO -> {
            // Crown plates peeking over the head silhouette.
            val plates = listOf(Triple(33f, 15f, 7f), Triple(50f, 10f, 9f), Triple(67f, 15f, 7f))
            plates.forEach { (cx, cy, r) ->
                drawArc(
                    color = spec.detail,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = Offset((cx - r) * u, (cy - r) * u - raise),
                    size = Size(2 * r * u, 2 * r * u)
                )
            }
        }
        Avatars.ZEBRA -> {
            val lift = perk * 2.2f
            listOf(-1f to 30f, 1f to 70f).forEach { (side, cx) ->
                rotate(side * 14f, pivot = Offset(cx * u, 20f * u)) {
                    drawOval(
                        spec.bodyShade.copy(alpha = 0.5f),
                        topLeft = Offset((cx - 6f) * u, (3.5f - lift) * u),
                        size = Size(12f * u, 19f * u)
                    )
                    drawOval(
                        spec.body,
                        topLeft = Offset((cx - 5.2f) * u, (4.3f - lift) * u),
                        size = Size(10.4f * u, 17f * u)
                    )
                    drawOval(
                        spec.innerEar,
                        topLeft = Offset((cx - 2.8f) * u, (6.8f - lift) * u),
                        size = Size(5.6f * u, 11f * u)
                    )
                }
            }
            // Bristly mane hood over the crown.
            drawArc(
                color = spec.detail,
                startAngle = 205f,
                sweepAngle = 130f,
                useCenter = false,
                topLeft = Offset((50f - 27f) * u, (36f - 27f) * u),
                size = Size(54f * u, 54f * u),
                style = Stroke(6f * u, cap = StrokeCap.Round)
            )
        }
        else -> {
            // Cheetah round ears.
            drawCheetahEar(29f, raise, u, spec)
            drawCheetahEar(71f, raise, u, spec)
        }
    }
}

private fun DrawScope.drawCheetahEar(cx: Float, raise: Float, u: Float, spec: AvatarSpec) {
    val cy = 17f * u - raise
    drawCircle(spec.bodyShade, 7.5f * u, Offset(cx * u, cy))
    drawCircle(spec.innerEar, 4f * u, Offset(cx * u, cy + 0.8f * u))
}

private fun DrawScope.drawSpeciesMarks(spec: AvatarSpec, u: Float) {
    when (spec.id) {
        Avatars.CHEETAH -> {
            listOf(41f to 16f, 50f to 13f, 59f to 16f).forEach { (x, y) ->
                drawCircle(spec.detail, 2.1f * u, Offset(x * u, y * u))
            }
            val tears = Path().apply {
                moveTo(38f * u, 40f * u)
                quadraticBezierTo(33f * u, 46f * u, 31.5f * u, 53f * u)
                moveTo(62f * u, 40f * u)
                quadraticBezierTo(67f * u, 46f * u, 68.5f * u, 53f * u)
            }
            drawPath(tears, spec.detail, style = Stroke(1.8f * u, cap = StrokeCap.Round))
        }
        Avatars.ZEBRA -> {
            val stripes = Path().apply {
                moveTo(44f * u, 21f * u); lineTo(41f * u, 12.5f * u)
                moveTo(50f * u, 20f * u); lineTo(50f * u, 10.5f * u)
                moveTo(56f * u, 21f * u); lineTo(59f * u, 12.5f * u)
            }
            drawPath(stripes, spec.detail, style = Stroke(1.7f * u, cap = StrokeCap.Round))
            drawLine(
                spec.detail,
                Offset(26.5f * u, 44f * u), Offset(31.5f * u, 44f * u),
                strokeWidth = 1.5f * u, cap = StrokeCap.Round
            )
            drawLine(
                spec.detail,
                Offset(73.5f * u, 44f * u), Offset(68.5f * u, 44f * u),
                strokeWidth = 1.5f * u, cap = StrokeCap.Round
            )
        }
        else -> Unit
    }
}

private fun DrawScope.drawMuzzleNose(spec: AvatarSpec, u: Float) {
    when (spec.id) {
        Avatars.CHEETAH -> {
            drawOval(
                spec.muzzle,
                topLeft = Offset(41f * u, 39f * u),
                size = Size(18f * u, 13f * u)
            )
            drawCircle(Color(0xFF7A4E22), 2.6f * u, Offset(50f * u, 42.5f * u))
        }
        Avatars.ZEBRA -> {
            drawOval(
                spec.muzzle,
                topLeft = Offset(39.5f * u, 37.5f * u),
                size = Size(21f * u, 16f * u)
            )
            drawCircle(Color(0xFF4A4741), 1.5f * u, Offset(45.5f * u, 43.5f * u))
            drawCircle(Color(0xFF4A4741), 1.5f * u, Offset(54.5f * u, 43.5f * u))
        }
        else -> {
            // Dino nostrils only.
            drawCircle(Color(0xFF4E7A3C), 1.4f * u, Offset(45.5f * u, 38.5f * u))
            drawCircle(Color(0xFF4E7A3C), 1.4f * u, Offset(54.5f * u, 38.5f * u))
        }
    }
}

private fun DrawScope.drawEye(ex: Float, ey: Float, pose: Pose, u: Float) {
    if (pose.happyEyes) {
        drawArc(
            INK,
            startAngle = 190f,
            sweepAngle = 160f,
            useCenter = false,
            topLeft = Offset((ex - 5f) * u, (ey - 5f) * u),
            size = Size(10f * u, 10f * u),
            style = Stroke(2.4f * u, cap = StrokeCap.Round)
        )
        return
    }
    translate(left = pose.lookX * 2.2f * u, top = pose.lookY * 1.6f * u) {
        val openH = maxOf(pose.eyeOpen, 0.12f)
        scale(scaleX = 1f, scaleY = openH, pivot = Offset(ex * u, ey * u)) {
            drawCircle(INK, radius = 5.2f * pose.pupilScale * u, center = Offset(ex * u, ey * u))
            drawCircle(SHINE.copy(alpha = 0.95f), 1.75f * u, Offset((ex - 1.7f) * u, (ey - 1.9f) * u))
            drawCircle(SHINE.copy(alpha = 0.8f), 0.9f * u, Offset((ex + 1.6f) * u, (ey + 1.5f) * u))
        }
        if (pose.eyeOpen < 0.5f) {
            // Heavy sleepy lid: body-colored cover plus a lash line at its edge.
            drawOval(
                Color.Black.copy(alpha = 0.08f),
                topLeft = Offset((ex - 5.4f) * u, (ey - 5.8f) * u),
                size = Size(10.8f * u, 5.6f * u)
            )
            drawLine(
                INK.copy(alpha = 0.65f),
                Offset((ex - 4.6f) * u, (ey - 1.2f) * u),
                Offset((ex + 4.6f) * u, (ey - 1.2f) * u),
                strokeWidth = 1.1f * u,
                cap = StrokeCap.Round
            )
        }
    }
}

private fun DrawScope.drawEyes(pose: Pose, u: Float) {
    drawEye(39f, 33.5f, pose, u)
    drawEye(61f, 33.5f, pose, u)
}

private fun DrawScope.drawBrows(pose: Pose, u: Float) {
    if (pose.sadBrows) {
        drawLine(
            INK, Offset(34f * u, 26f * u), Offset(43.5f * u, 23f * u),
            strokeWidth = 1.7f * u, cap = StrokeCap.Round
        )
        drawLine(
            INK, Offset(66f * u, 26f * u), Offset(56.5f * u, 23f * u),
            strokeWidth = 1.7f * u, cap = StrokeCap.Round
        )
        return
    }
    val ly = 24f - pose.browRaiseL * 3f
    val ry = 24f - pose.browRaiseR * 3f
    drawLine(
        INK, Offset(35f * u, ly * u), Offset(43f * u, ly * u),
        strokeWidth = 1.6f * u, cap = StrokeCap.Round
    )
    drawLine(
        INK, Offset(57f * u, ry * u), Offset(65f * u, ry * u),
        strokeWidth = 1.6f * u, cap = StrokeCap.Round
    )
}

private fun DrawScope.drawBlush(u: Float) {
    drawCircle(BLUSH, 3.6f * u, Offset(30.5f * u, 43.5f * u))
    drawCircle(BLUSH, 3.6f * u, Offset(69.5f * u, 43.5f * u))
}

private fun DrawScope.drawMouth(pose: Pose, u: Float) {
    when {
        pose.frown -> drawArc(
            INK,
            startAngle = 200f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(45f * u, 48f * u),
            size = Size(10f * u, 10f * u),
            style = Stroke(1.8f * u, cap = StrokeCap.Round)
        )
        pose.mouthOpen > 0.02f -> {
            val h = 1.5f + 6.5f * minOf(pose.mouthOpen, 1f)
            val mouthPath = Path().apply {
                addOval(
                    Rect(
                        left = (50f - 4.2f) * u,
                        top = (48f - h) * u,
                        right = (50f + 4.2f) * u,
                        bottom = (48f + h) * u
                    )
                )
            }
            drawPath(mouthPath, INK)
            clipPath(mouthPath) {
                drawOval(
                    TONGUE,
                    topLeft = Offset(47.4f * u, (48f + h * 0.25f) * u),
                    size = Size(5.2f * u, h * 0.8f * u)
                )
            }
        }
        else -> drawArc(
            INK,
            startAngle = 25f,
            sweepAngle = 130f,
            useCenter = false,
            topLeft = Offset(45f * u, 43.5f * u),
            size = Size(10f * u, 10f * u),
            style = Stroke(1.9f * u, cap = StrokeCap.Round)
        )
    }
}

private fun DrawScope.drawSweat(u: Float) {
    val drop = Path().apply {
        moveTo(71.5f * u, 18.5f * u)
        quadraticBezierTo(75.2f * u, 23.5f * u, 71.5f * u, 26.5f * u)
        quadraticBezierTo(67.8f * u, 23.5f * u, 71.5f * u, 18.5f * u)
        close()
    }
    drawPath(drop, SWEAT_BLUE.copy(alpha = 0.92f))
}

// ---------------------------------------------------------------------------
// Overlays
// ---------------------------------------------------------------------------

private fun DrawScope.drawZGlyph(x: Float, y: Float, w: Float, alpha: Float, u: Float) {
    val h = w
    val path = Path().apply {
        moveTo(x * u, y * u)
        lineTo((x + w) * u, y * u)
        lineTo(x * u, (y + h) * u)
        lineTo((x + w) * u, (y + h) * u)
    }
    drawPath(path, ZZZ_COLOR.copy(alpha = alpha), style = Stroke(w * 0.28f * u, cap = StrokeCap.Round))
}

private fun DrawScope.drawZzz(u: Float, t: Float) {
    val zs = listOf(
        Triple(64f, 22f, 4.5f),
        Triple(71f, 14f, 6.5f),
        Triple(79f, 5f, 9f)
    )
    zs.forEachIndexed { i, (x, y, s) ->
        val phase = frac(t + i * 0.18f)
        val alpha = sin(phase * PI.toFloat()).coerceIn(0f, 1f) * 0.85f
        drawZGlyph(x, y - phase * 3f, s, alpha, u)
    }
}

private fun DrawScope.drawQuestionBubble(u: Float, t: Float) {
    val bob = sin((2.0 * PI).toFloat() * t * 2f) * 1.5f
    val edge = Stroke(1.1f * u)
    drawCircle(BUBBLE_FILL, 1.7f * u, Offset(66.5f * u, (21.5f + bob) * u))
    drawCircle(BUBBLE_EDGE, 1.7f * u, Offset(66.5f * u, (21.5f + bob) * u), style = edge)
    drawCircle(BUBBLE_FILL, 2.6f * u, Offset(70.5f * u, (17f + bob) * u))
    drawCircle(BUBBLE_EDGE, 2.6f * u, Offset(70.5f * u, (17f + bob) * u), style = edge)
    drawCircle(BUBBLE_FILL, 7f * u, Offset(79f * u, (8f + bob) * u))
    drawCircle(BUBBLE_EDGE, 7f * u, Offset(79f * u, (8f + bob) * u), style = edge)
    // Question-mark hook: arc over the top curling into a stem and dot.
    drawArc(
        QUESTION_COLOR,
        startAngle = 160f,
        sweepAngle = 220f,
        useCenter = false,
        topLeft = Offset((79f - 2.8f) * u, (7.2f + bob - 2.8f) * u),
        size = Size(5.6f * u, 5.6f * u),
        style = Stroke(1.7f * u, cap = StrokeCap.Round)
    )
    drawLine(
        QUESTION_COLOR,
        Offset((79f + 2.63f) * u, (8.18f + bob) * u),
        Offset(79f * u, (10.4f + bob) * u),
        strokeWidth = 1.7f * u,
        cap = StrokeCap.Round
    )
    drawCircle(QUESTION_COLOR, 1.05f * u, Offset(79f * u, (12.6f + bob) * u))
}

private fun sparklePath(cx: Float, cy: Float, r: Float, u: Float): Path = Path().apply {
    moveTo(cx * u, (cy - r) * u)
    quadraticBezierTo(cx * u, cy * u, (cx + r) * u, cy * u)
    quadraticBezierTo(cx * u, cy * u, cx * u, (cy + r) * u)
    quadraticBezierTo(cx * u, cy * u, (cx - r) * u, cy * u)
    quadraticBezierTo(cx * u, cy * u, cx * u, (cy - r) * u)
    close()
}

private fun DrawScope.drawSparkles(u: Float, t: Float) {
    val spots = listOf(
        Triple(15f, 20f, SPARKLE_GOLD),
        Triple(26f, 7f, SHINE),
        Triple(50f, 1f, SPARKLE_GOLD),
        Triple(74f, 7f, SHINE),
        Triple(85f, 20f, SPARKLE_GOLD)
    )
    spots.forEachIndexed { i, (x, y, color) ->
        val phase = frac(t * 3f + i * 0.17f)
        val twinkle = sin(phase * PI.toFloat()).coerceIn(0f, 1f)
        drawPath(
            sparklePath(x, y, 3.4f + 1.6f * twinkle, u),
            color.copy(alpha = 0.25f + 0.75f * twinkle)
        )
    }
}

// ---------------------------------------------------------------------------

private fun frac(v: Float): Float = v - floor(v)
