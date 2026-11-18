package app.inkwell.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import app.inkwell.ui.motion.LocalReducedMotion
import app.inkwell.ui.motion.Motion
import app.inkwell.ui.motion.project
import app.inkwell.ui.motion.rubberBand
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Pinch-zoom + pan + double-tap zoom with rubber-banding at the bounds and a bouncy spring on release.
 * Gestures are only consumed when zoomed or multi-touch, so parents (pager / list) keep scrolling at 1x.
 */
@Composable
fun Modifier.zoomable(
    maxScale: Float = 4f,
    doubleTapScale: Float = 2.5f,
    onTap: (Offset) -> Unit = {},
    onLongPress: ((Offset) -> Unit)? = null,
    onZoomChanged: (Boolean) -> Unit = {},
): Modifier {
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    val scale = remember { Animatable(1f) }
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val onZoom by rememberUpdatedState(onZoomChanged)
    val onTapState by rememberUpdatedState(onTap)
    val onLongState by rememberUpdatedState(onLongPress)

    LaunchedEffect(Unit) { snapshotFlow { scale.value > 1.02f }.collect { onZoom(it) } }

    fun bounds(s: Float) = Offset(max(0f, (s - 1f) * size.width / 2f), max(0f, (s - 1f) * size.height / 2f))
    fun clamp(o: Offset, s: Float): Offset {
        val b = bounds(s)
        return Offset(o.x.coerceIn(-b.x, b.x), o.y.coerceIn(-b.y, b.y))
    }
    fun rubberScale(raw: Float): Float = when {
        raw < 1f -> 1f - rubberBand(1f - raw, 1f).coerceAtMost(0.4f)
        raw > maxScale -> maxScale + rubberBand(raw - maxScale, maxScale)
        else -> raw
    }
    fun rubberOffset(raw: Offset, s: Float): Offset {
        val c = clamp(raw, s)
        return Offset(
            c.x + rubberBand(raw.x - c.x, size.width.toFloat()),
            c.y + rubberBand(raw.y - c.y, size.height.toFloat()),
        )
    }

    return this
        .onSizeChanged { size = it }
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { onTapState(it) },
                onLongPress = { off -> onLongState?.invoke(off) },
                onDoubleTap = { tap ->
                    scope.launch {
                        if (scale.value > 1.05f) {
                            launch { scale.animateTo(1f, Motion.Default.specFor(reduced)) }
                            offset.animateTo(Offset.Zero, Motion.Default.specFor(reduced))
                        } else {
                            val rel = tap - Offset(size.width / 2f, size.height / 2f)
                            val target = clamp(rel * (1f - doubleTapScale), doubleTapScale)
                            launch { scale.animateTo(doubleTapScale, Motion.Default.specFor(reduced)) }
                            offset.animateTo(target, Motion.Default.specFor(reduced))
                        }
                    }
                },
            )
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                scope.launch(start = CoroutineStart.UNDISPATCHED) { scale.stop(); offset.stop() }
                var rawScale = scale.value
                var rawOffset = offset.value
                val tracker = VelocityTracker()
                var touched = false
                do {
                    val event = awaitPointerEvent()
                    val cancelled = event.changes.any { it.isConsumed }
                    if (!cancelled) {
                        val multi = event.changes.count { it.pressed } > 1
                        if (multi || rawScale > 1.01f) {
                            touched = true
                            val z = event.calculateZoom()
                            val pan = event.calculatePan()
                            val c = event.calculateCentroid(useCurrent = false)
                                .let { if (it.isUnspecified) Offset.Zero else it - Offset(size.width / 2f, size.height / 2f) }
                            rawScale *= z
                            rawOffset = (rawOffset - c) * z + c + pan
                            val s = rubberScale(rawScale)
                            val o = rubberOffset(rawOffset, s)
                            scope.launch(start = CoroutineStart.UNDISPATCHED) { scale.snapTo(s); offset.snapTo(o) }
                            event.changes.forEach {
                                tracker.addPosition(it.uptimeMillis, it.position)
                                if (it.positionChanged()) it.consume()
                            }
                        }
                    }
                } while (!cancelled && event.changes.any { it.pressed })

                if (touched) {
                    val v = tracker.calculateVelocity()
                    val targetScale = rawScale.coerceIn(1f, maxScale)
                    val fling = if (targetScale > 1.01f) Offset(project(v.x), project(v.y)) * 0.25f else Offset.Zero
                    val target = clamp(rawOffset + fling, targetScale)
                    scope.launch {
                        launch { scale.animateTo(targetScale, Motion.Default.specFor(reduced)) }
                        offset.animateTo(target, Motion.Bouncy.specFor(reduced), initialVelocity = Offset(v.x, v.y))
                    }
                }
            }
        }
        .graphicsLayer {
            scaleX = scale.value; scaleY = scale.value
            translationX = offset.value.x; translationY = offset.value.y
        }
}
