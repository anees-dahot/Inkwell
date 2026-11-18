package app.inkwell.ui.motion

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.abs

/** True when system animations are off (ANIMATOR_DURATION_SCALE == 0). Springs become short crossfades. */
val LocalReducedMotion = compositionLocalOf { false }

object Motion {
    /** Apple "response" (seconds) -> spring stiffness = (2*pi/response)^2 */
    fun stiffness(response: Float): Float = ((2.0 * PI / response) * (2.0 * PI / response)).toFloat()

    class Token(val damping: Float, val response: Float) {
        val stiffness: Float = stiffness(response)
        fun <T> spec(): SpringSpec<T> = spring(dampingRatio = damping, stiffness = stiffness)
        /** Spring, or a short tween when reduced motion is on. */
        fun <T> specFor(reduced: Boolean): FiniteAnimationSpec<T> =
            if (reduced) tween(120) else spring(dampingRatio = damping, stiffness = stiffness)
    }

    val Default = Token(1.0f, 0.35f)   // stiffness ~322
    val Snappy = Token(1.0f, 0.25f)
    val Bouncy = Token(0.8f, 0.35f)    // only after momentum gestures (fling / release)
    val Sheet = Token(0.8f, 0.30f)
}

/** UIScrollView-style deceleration projection: where a fling at [v] px/s would come to rest. */
fun project(v: Float): Float = (v / 1000f) * 0.998f / (1f - 0.998f)

/** Rubber-band resistance: distance [x] past an edge, [d] = dimension of the viewport. */
fun rubberBand(x: Float, d: Float): Float {
    if (d <= 0f) return 0f
    val s = if (x < 0) -1f else 1f
    val ax = abs(x)
    return s * (ax * d * 0.55f / (d + 0.55f * ax))
}

/** Scales to 0.97 and dims on press DOWN, springs back on release. No ripple. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.pressable(
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(
        if (pressed && !reduced) 0.97f else 1f, Motion.Snappy.specFor(reduced), label = "pressScale",
    )
    val alpha by animateFloatAsState(
        if (pressed) 0.7f else 1f, Motion.Snappy.specFor(reduced), label = "pressAlpha",
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
        .combinedClickable(
            interactionSource = src, indication = null, enabled = enabled,
            onLongClick = onLongClick, onClick = onClick,
        )
}
