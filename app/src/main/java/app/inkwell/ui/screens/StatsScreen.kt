package app.inkwell.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.inkwell.data.ReadingStats
import app.inkwell.data.StatsCalc
import app.inkwell.data.model.ContentType
import app.inkwell.data.model.Entry
import app.inkwell.ui.components.*
import app.inkwell.ui.motion.pressable
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import java.time.format.TextStyle
import java.util.Locale

@Composable
private fun StatRow(label: String, value: String) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = InkType.body, color = ink.label, modifier = Modifier.weight(1f))
        Text(value, style = InkType.body, color = ink.secondary)
    }
}

@Composable
fun StatsScreen(onBack: () -> Unit, onOpenEntry: (Entry) -> Unit) {
    val c = LocalContainer.current
    val ink = LocalInk.current
    val hazeState = remember { HazeState() }
    val list = rememberLazyListState()
    val collapsed by list.rememberCollapsed()
    val stats by remember {
        val d = c.db.stats()
        combine(d.observeRead(), d.observeSessions(), d.libraryTitles(), d.historyTitles()) { read, sessions, lib, hist ->
            val titles = HashMap<Pair<String, String>, String>()
            hist.forEach { titles[it.sourceId to it.entryId] = it.title }
            lib.forEach { titles[it.sourceId to it.entryId] = it.title }
            StatsCalc.compute(read, sessions, titles, { c.registry.find(it)?.type ?: ContentType.MANGA })
        }.flowOn(Dispatchers.Default)
    }.collectAsStateWithLifecycle(null)
    var chartMode by remember { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize().background(ink.bg)) {
        LazyColumn(
            state = list, modifier = Modifier.fillMaxSize().hazeSource(hazeState),
            contentPadding = PaddingValues(top = topContentPadding(), bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp),
        ) {
            item { LargeTitle("Statistics") }
            val s = stats
            if (s != null) {
                item { SectionHeader("Overview") }
                item {
                    GroupedCard {
                        StatRow("Chapters read", s.totalChapters.toString())
                        Hairline()
                        StatRow("Time spent reading", StatsCalc.formatDuration(s.totalTimeMs))
                        Hairline()
                        StatRow("Current streak", days(s.currentStreak))
                        Hairline()
                        StatRow("Longest streak", days(s.longestStreak))
                    }
                }
                item { SectionHeader("By type") }
                item {
                    GroupedCard {
                        val types = listOf(ContentType.MANGA to "Manga", ContentType.NOVEL to "Novels")
                        types.forEachIndexed { i, (t, label) ->
                            val n = s.chaptersByType[t] ?: 0
                            val time = s.timeByType[t] ?: 0L
                            StatRow(label, "$n read" + if (time > 0) "  ·  ${StatsCalc.formatDuration(time)}" else "")
                            if (i < types.lastIndex) Hairline()
                        }
                    }
                }
                item { SectionHeader("Last 30 days") }
                item {
                    GroupedCard {
                        Column(Modifier.padding(16.dp)) {
                            SegmentedControl(listOf("Minutes", "Chapters"), chartMode, { chartMode = it })
                            Spacer(Modifier.height(12.dp))
                            BarChart(s, chartMode == 0)
                        }
                    }
                }
                item { SectionHeader("Top titles") }
                item {
                    if (s.top.isEmpty()) Text("Read something and it will show up here.", style = InkType.callout, color = ink.secondary, modifier = Modifier.padding(horizontal = 32.dp))
                    else GroupedCard {
                        s.top.forEachIndexed { i, t ->
                            Row(
                                Modifier.fillMaxWidth().pressable {
                                    onOpenEntry(Entry(t.sourceId, t.entryId, t.title, null, c.registry.find(t.sourceId)?.type ?: ContentType.MANGA))
                                }.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("${i + 1}", style = InkType.headline, color = ink.accent, modifier = Modifier.width(24.dp))
                                Text(t.title, style = InkType.body, color = ink.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${t.chapters} ch" + if (t.timeMs > 0) "  ·  ${StatsCalc.formatDuration(t.timeMs)}" else "",
                                    style = InkType.callout, color = ink.secondary,
                                )
                            }
                            if (i < s.top.lastIndex) Hairline()
                        }
                    }
                }
                item {
                    Text(
                        "Reading time is tracked while a reader or player is open. Nothing is recorded in incognito mode.",
                        style = InkType.caption, color = ink.secondary, modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
                    )
                }
            }
        }
        GlassTopBar(
            "Statistics", collapsed, hazeState, Modifier.align(Alignment.TopCenter),
            leading = { BarIconButton(Icons.AutoMirrored.Outlined.ArrowBackIos, "Back", onClick = onBack) },
        )
    }
}

private fun days(n: Int) = if (n == 1) "1 day" else "$n days"

@Composable
private fun BarChart(s: ReadingStats, minutes: Boolean) {
    val ink = LocalInk.current
    val values = s.last30.map { if (minutes) it.minutes else it.chapters.toFloat() }
    val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
    val label = if (minutes) "${max.toInt().coerceAtLeast(1)} min" else "${max.toInt()} ch"
    Text(label, style = InkType.caption, color = ink.secondary)
    Spacer(Modifier.height(4.dp))
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val n = values.size
        val gap = 3.dp.toPx()
        val bw = (size.width - gap * (n - 1)) / n
        values.forEachIndexed { i, v ->
            val h = if (v <= 0f) 2.dp.toPx() else (v / max * size.height).coerceAtLeast(4.dp.toPx())
            val x = i * (bw + gap)
            drawRoundRect(
                color = if (v <= 0f) ink.separator else ink.accent,
                topLeft = Offset(x, size.height - h), size = Size(bw, h), cornerRadius = CornerRadius(bw.coerceAtMost(4.dp.toPx()) / 2),
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth()) {
        val first = s.last30.first().day
        val last = s.last30.last().day
        Text("${first.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${first.dayOfMonth}", style = InkType.caption, color = ink.secondary, modifier = Modifier.weight(1f))
        Text("Today", style = InkType.caption, color = ink.secondary)
    }
}
