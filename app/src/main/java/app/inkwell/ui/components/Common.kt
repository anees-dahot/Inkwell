package app.inkwell.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import app.inkwell.data.model.Entry
import app.inkwell.ui.motion.LocalReducedMotion
import app.inkwell.ui.motion.Motion
import app.inkwell.ui.motion.pressable
import app.inkwell.ui.theme.CardRadius
import app.inkwell.ui.theme.CoverRadius
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk
import coil3.compose.AsyncImage

@Composable
fun Modifier.shimmer(): Modifier {
    val reduced = LocalReducedMotion.current
    val ink = LocalInk.current
    val a = if (reduced) 0.12f else {
        val t = rememberInfiniteTransition(label = "shimmer")
        val v by t.animateFloat(0.08f, 0.2f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s")
        v
    }
    return this.background(ink.secondary.copy(alpha = a))
}

@Composable
fun Cover(url: String?, modifier: Modifier = Modifier, ratio: Float = 2f / 3f) {
    val ink = LocalInk.current
    Box(
        modifier.aspectRatio(ratio).clip(RoundedCornerShape(CoverRadius)).background(ink.secondary.copy(alpha = 0.12f)),
    ) {
        if (url != null) AsyncImage(url, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(LocalInk.current.accent).padding(horizontal = 6.dp, vertical = 2.dp),
    ) { Text(if (count > 99) "99+" else "$count", color = Color.White, fontSize = 11.sp) }
}

@Composable
fun EntryGridItem(
    entry: Entry,
    modifier: Modifier = Modifier,
    badge: Int = 0,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val ink = LocalInk.current
    Column(modifier.pressable(onLongClick = onLongClick, onClick = onClick)) {
        Box {
            Cover(entry.coverUrl, Modifier.fillMaxWidth().sharedCover("cover-${entry.sourceId}-${entry.id}"))
            UnreadBadge(badge, Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            entry.title, style = InkType.caption.copy(fontSize = 13.sp, letterSpacing = 0.sp), color = ink.label,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SkeletonGridItem() {
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(CoverRadius)).shimmer())
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(0.8f).height(10.dp).clip(RoundedCornerShape(5.dp)).shimmer())
    }
}

@Composable
fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    Column(
        modifier.fillMaxWidth().padding(vertical = 64.dp, horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = ink.secondary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(text, style = InkType.callout, color = ink.secondary, textAlign = TextAlign.Center)
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier, webViewUrl: String? = null) {
    val ink = LocalInk.current
    val openWebView = LocalOpenWebView.current
    val isCf = webViewUrl != null && (message.contains("cloudflare", true) || message.contains("HTTP 403") || message.contains("HTTP 503"))
    var opened by remember { mutableStateOf(false) }
    if (isCf) {
        val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        val retry by rememberUpdatedState(onRetry)
        DisposableEffect(owner) {
            val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && opened) { opened = false; retry() }
            }
            owner.lifecycle.addObserver(obs)
            onDispose { owner.lifecycle.removeObserver(obs) }
        }
    }
    Column(
        modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.ErrorOutline, null, tint = ink.secondary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(message, style = InkType.callout, color = ink.secondary, textAlign = TextAlign.Center, maxLines = 3)
        Spacer(Modifier.height(16.dp))
        FilledPill("Retry", onClick = onRetry)
        if (isCf) {
            Spacer(Modifier.height(10.dp))
            FilledPill("Open in WebView", filled = false) { opened = true; openWebView(webViewUrl!!) }
        }
    }
}

/** Opens the in-app WebView (Cloudflare check / browsing) for a URL. Provided in AppNav. */
val LocalOpenWebView = staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
fun FilledPill(text: String, modifier: Modifier = Modifier, filled: Boolean = true, onClick: () -> Unit) {
    val ink = LocalInk.current
    Box(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(if (filled) ink.accent else ink.accent.copy(alpha = 0.14f))
            .pressable(onClick = onClick).padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (filled) Color.White else ink.accent, style = InkType.callout.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
    }
}

@Composable
fun Chip(text: String, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val ink = LocalInk.current
    val m = Modifier.clip(RoundedCornerShape(50)).background(if (selected) ink.accent else ink.card)
    Box(
        (if (onClick != null) m.pressable(onClick = onClick) else m).padding(horizontal = 14.dp, vertical = 7.dp),
    ) { Text(text, color = if (selected) Color.White else ink.label, style = InkType.callout.copy(fontSize = 14.sp)) }
}

/** iOS segmented control with a spring-animated thumb. */
@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    var widthPx by remember { mutableIntStateOf(0) }
    val segW = if (options.isEmpty()) 0f else widthPx / options.size.toFloat()
    val x by animateFloatAsState(selected * segW, Motion.Default.specFor(reduced), label = "seg")
    Box(
        modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(9.dp))
            .background(ink.secondary.copy(alpha = 0.14f)).padding(2.dp).onSizeChanged { widthPx = it.width },
    ) {
        if (widthPx > 0) Box(
            Modifier.offset { IntOffset(x.toInt(), 0) }
                .width(with(density) { segW.toDp() }).fillMaxHeight()
                .clip(RoundedCornerShape(7.dp)).background(if (ink.dark) Color(0xFF636366) else Color.White)
        )
        Row(Modifier.matchParentSize()) {
            options.forEachIndexed { i, o ->
                Box(Modifier.weight(1f).fillMaxHeight().pressable { onSelect(i) }, contentAlignment = Alignment.Center) {
                    Text(o, fontSize = 13.sp, fontWeight = if (i == selected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal, color = ink.label, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun LargeTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title, style = InkType.largeTitle, color = LocalInk.current.label,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** Hairline, inset from the leading edge. */
@Composable
fun Hairline(startInset: androidx.compose.ui.unit.Dp = 16.dp) {
    Box(Modifier.fillMaxWidth().padding(start = startInset).height(0.5.dp).background(LocalInk.current.separator))
}

/** Grouped inset card (rounded 12dp). */
@Composable
fun GroupedCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(CardRadius)).background(LocalInk.current.card),
        content = content,
    )
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text.uppercase(), style = InkType.caption.copy(fontSize = 13.sp, letterSpacing = 0.sp), color = LocalInk.current.secondary,
        modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 24.dp, bottom = 6.dp),
    )
}
