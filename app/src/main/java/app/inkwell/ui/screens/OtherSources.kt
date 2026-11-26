package app.inkwell.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.inkwell.AppContainer
import app.inkwell.data.model.Entry
import app.inkwell.data.model.PagedResult
import app.inkwell.data.model.isAdult
import app.inkwell.data.source.SourceHealth
import app.inkwell.data.source.fanOut
import app.inkwell.data.source.sameTitle
import app.inkwell.ui.components.Cover
import app.inkwell.ui.components.inkViewModel
import app.inkwell.ui.components.shimmer
import app.inkwell.ui.motion.pressable
import app.inkwell.ui.theme.CardRadius
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Searches [src] by each name in turn and returns the first result that really is the series
 * (single-item page), or an empty page.
 */
suspend fun searchAny(src: app.inkwell.data.model.Source, titles: List<String>): PagedResult<Entry> {
    for (t in titles) {
        val r = src.search(t, 1)
        val hit = r.items.firstOrNull { e -> titles.any { sameTitle(e.title, it) } }
        if (hit != null) return PagedResult(listOf(hit), false)
        // titles with a subtitle often only match on the main part
        if (t.contains(':')) {
            val r2 = src.search(t.substringBefore(':').trim(), 1)
            r2.items.firstOrNull { e -> titles.any { sameTitle(e.title, it) } }?.let { return PagedResult(listOf(it), false) }
        }
    }
    return PagedResult(emptyList(), false)
}

/** One copy of a title on some source, with its chapter count once known. */
class SourceCopy(val entry: Entry, chapters: Int? = null, latest: Float? = null) {
    var chapters by mutableStateOf(chapters)
    var latest by mutableStateOf(latest)
    var failed by mutableStateOf(false)
}

/** Looks a title up on every source of its type so the reader can compare and pick a source. */
class OtherSourcesViewModel(private val c: AppContainer, private val origin: Entry) : ViewModel() {
    var copies by mutableStateOf(listOf(SourceCopy(origin))); private set
    var searching by mutableStateOf(true); private set
    var progress by mutableStateOf(0 to 0); private set
    private val countSem = Semaphore(3)

    init { run() }

    fun run() {
        copies = listOf(SourceCopy(origin))
        count(copies.first())
        viewModelScope.launch {
            searching = true
            // 18+ sources only when enabled, or when the title itself already comes from one
            val allowAdult = c.settings.showAdult.value || origin.adult || c.registry.find(origin.sourceId)?.isAdult == true
            val others = c.registry.sources.filter { it.type == origin.type && it.id != origin.sourceId && it !is app.inkwell.data.model.CatalogOnly && !SourceHealth.benched(it.id) &&
                (allowAdult || !it.isAdult) }
            progress = 0 to others.size
            val titles = (listOf(origin.title) + runCatching { c.repo.details(origin.sourceId, origin.id).altTitles }.getOrDefault(emptyList()))
                .map { it.trim() }.filter { t -> t.length >= 3 && t.count { it.code < 0x250 } * 2 >= t.length }
                .distinctBy { app.inkwell.data.source.titleKey(it) }.take(3)
            fanOut(others, op = { src -> searchAny(src, titles) }) { _, r: PagedResult<Entry>? ->
                progress = (progress.first + 1) to progress.second
                val hit = r?.items?.firstOrNull() ?: return@fanOut
                if (copies.any { it.entry.sourceId == hit.sourceId }) return@fanOut
                val copy = SourceCopy(hit)
                copies = copies + copy
                count(copy)
            }
            searching = false
        }
    }

    private fun count(copy: SourceCopy) {
        viewModelScope.launch {
            countSem.withPermit {
                try {
                    val eps = c.repo.episodes(copy.entry.sourceId, copy.entry.id)
                    copy.chapters = eps.size
                    copy.latest = eps.mapNotNull { it.number }.maxOrNull()
                } catch (e: CancellationException) { throw e } catch (_: Exception) { copy.failed = true }
            }
        }
    }

    fun sourceName(id: String) = c.registry.find(id)?.name?.substringBefore(" (") ?: id
    val originId get() = origin.sourceId
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtherSourcesSheet(entry: Entry, onDismiss: () -> Unit, onOpen: (Entry) -> Unit) {
    val ink = LocalInk.current
    val vm = inkViewModel("othersrc/${entry.sourceId}/${entry.id}") { OtherSourcesViewModel(it, entry) }
    // best first: this source, then the most chapters (newest chapter number as tiebreak)
    val sorted = vm.copies.sortedWith(
        compareBy<SourceCopy>({ if (it.entry.sourceId == vm.originId) 0 else 1 })
            .thenByDescending { it.latest ?: -1f }
            .thenByDescending { it.chapters ?: -1 }
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ink.bg,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).navigationBarsPadding()) {
            Text("On other sources", style = InkType.headline, color = ink.label, modifier = Modifier.padding(horizontal = 16.dp))
            val (done, total) = vm.progress
            Text(
                if (vm.searching) "Checking $done of $total sources…" else "Found on ${vm.copies.size} source${if (vm.copies.size == 1) "" else "s"}",
                style = InkType.caption, color = ink.secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            if (vm.searching) LinearProgressIndicator(
                progress = { if (total == 0) 0f else done / total.toFloat() },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = ink.accent, trackColor = ink.secondary.copy(alpha = 0.15f),
            ) else Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(sorted, key = { it.entry.sourceId + "/" + it.entry.id }) { copy ->
                    val here = copy.entry.sourceId == vm.originId
                    Row(
                        Modifier.animateItem().padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
                            .clip(RoundedCornerShape(CardRadius))
                            .background(if (here) ink.accent.copy(alpha = 0.10f) else ink.card)
                            .pressable { if (here) onDismiss() else onOpen(copy.entry) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(copy.entry.coverUrl, Modifier.width(44.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(vm.sourceName(copy.entry.sourceId), style = InkType.body.copy(fontWeight = FontWeight.SemiBold), color = ink.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (here) Text("  Current", style = InkType.caption, color = ink.accent)
                            }
                            Text(copy.entry.title, style = InkType.caption, color = ink.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(2.dp))
                            when {
                                copy.failed -> Text("Couldn't load chapters", style = InkType.caption, color = ink.secondary)
                                copy.chapters == null -> Box(Modifier.width(90.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).shimmer())
                                else -> Text(
                                    "${copy.chapters} chapters" + (copy.latest?.let { " · latest " + app.inkwell.work.Notifier.fmt(it) } ?: ""),
                                    style = InkType.caption, color = ink.label,
                                )
                            }
                        }
                        if (!here) Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, null, tint = ink.secondary, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}
