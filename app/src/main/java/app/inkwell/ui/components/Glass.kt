package app.inkwell.ui.components

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.inkwell.ui.motion.LocalReducedMotion
import app.inkwell.ui.motion.Motion
import app.inkwell.ui.motion.pressable
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity

/** Reduced transparency: blur needs API 31+, otherwise fall back to a (nearly) solid surface. */
val blurSupported: Boolean get() = Build.VERSION.SDK_INT >= 31

/** Translucent glass: ~20dp blur + 70% surface tint (solid-ish where blur is unavailable). */
@Composable
fun Modifier.glass(state: HazeState, dark: Boolean? = null, tintAlpha: Float = 0.7f): Modifier {
    val ink = LocalInk.current
    val base = if (dark == true) Color(0xFF1C1C1E) else if (dark == false) Color.White else ink.card
    val a = if (blurSupported) tintAlpha else 0.96f
    return this.hazeEffect(
        state = state,
        style = HazeStyle(
            backgroundColor = if (dark == true) Color.Black else ink.bg,
            tint = HazeTint(base.copy(alpha = a)),
            blurRadius = 20.dp,
            noiseFactor = 0f,
        ),
    )
}

@Composable
fun LazyListState.rememberCollapsed(thresholdDp: Dp = 36.dp): State<Boolean> {
    val px = with(LocalDensity.current) { thresholdDp.toPx() }
    return remember(this) { derivedStateOf { firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > px } }
}

@Composable
fun LazyGridState.rememberCollapsed(thresholdDp: Dp = 36.dp): State<Boolean> {
    val px = with(LocalDensity.current) { thresholdDp.toPx() }
    return remember(this) { derivedStateOf { firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > px } }
}

val TopBarHeight = 44.dp

/**
 * Glass compact top bar. Transparent over the large title; fades into blur + centered title once collapsed.
 */
@Composable
fun GlassTopBar(
    title: String,
    collapsed: Boolean,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val reduced = LocalReducedMotion.current
    val a by animateFloatAsState(if (collapsed) 1f else 0f, Motion.Default.specFor(reduced), label = "bar")
    val ink = LocalInk.current
    Box(modifier.fillMaxWidth()) {
        Box(
            Modifier.matchParentSize().alpha(a).glass(hazeState)
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(0.5.dp).alpha(a).background(ink.separator)
        )
        Column {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Box(Modifier.fillMaxWidth().height(TopBarHeight).padding(horizontal = 8.dp)) {
                Box(Modifier.align(Alignment.CenterStart)) { leading() }
                Text(
                    title, style = InkType.headline, color = ink.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp).alpha(a),
                )
                Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = trailing)
            }
        }
    }
}

@Composable
fun BarIconButton(icon: ImageVector, desc: String, tint: Color = LocalInk.current.accent, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(24.dp)) }
}

data class TabItem(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/** Glass bottom tab bar. */
@Composable
fun GlassTabBar(tabs: List<TabItem>, selected: Int, hazeState: HazeState, onSelect: (Int) -> Unit) {
    val ink = LocalInk.current
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize().glass(hazeState))
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(0.5.dp).background(ink.separator))
        Column {
            Row(Modifier.fillMaxWidth().height(49.dp)) {
                tabs.forEachIndexed { i, t ->
                    val sel = i == selected
                    val reduced = LocalReducedMotion.current
                    val s by animateFloatAsState(if (sel) 1.08f else 1f, Motion.Snappy.specFor(reduced), label = "tab")
                    Column(
                        Modifier.weight(1f).fillMaxHeight().pressable { onSelect(i) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        val tint = if (sel) ink.accent else ink.secondary
                        Icon(
                            if (sel) t.selectedIcon else t.icon, t.label, tint = tint,
                            modifier = Modifier.size(25.dp).graphicsLayer { scaleX = s; scaleY = s },
                        )
                        Text(t.label, fontSize = 10.sp, letterSpacing = 0.02.sp, color = tint, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}
