package app.inkwell.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.inkwell.AppContainer
import app.inkwell.data.MigrateResult
import app.inkwell.data.model.ContentType
import app.inkwell.data.model.Entry
import app.inkwell.ui.components.*
import app.inkwell.ui.motion.pressable
import app.inkwell.ui.theme.CardRadius
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** The library title being moved to another source. */
data class MigrateTarget(val sourceId: String, val entryId: String, val title: String, val coverUrl: String?, val type: ContentType)

data class Candidate(
    val entry: Entry, val sourceName: String, val score: Float,
    /** null = not (yet) counted. */
    val chapters: Int? = null, val counting: Boolean = false, val countFailed: Boolean = false,
)

internal fun normTitle(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

/** 0..1 closeness of two titles: exact = 1, else token overlap with a bonus for containment. */
internal fun titleScore(a: String, b: String): Float {
    val x = normTitle(a); val y = normTitle(b)
    if (x.isEmpty() || y.isEmpty()) return 0f
    if (x == y) return 1f
    val tx = x.split(' ').toSet(); val ty = y.split(' ').toSet()
    val jac = tx.intersect(ty).size.toFloat() / tx.union(ty).size
    val contain = if (x.contains(y) || y.contains(x)) 0.25f else 0f
    return (jac + contain).coerceAtMost(0.99f)
}

class MigrateViewModel(private val c: AppContainer, val target: MigrateTarget) : ViewModel() {
    var query by mutableStateOf(target.title); private set
    var candidates by mutableStateOf<List<Candidate>>(emptyList()); private set
    var finished by mutableIntStateOf(0); private set
    var total by mutableIntStateOf(0); private set
    var currentChapters by mutableStateOf<Int?>(null); private set
    var migrating by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private var job: Job? = null
    val running get() = total > 0 && finished < total
    val sourceName get() = c.registry.find(target.sourceId)?.name?.substringBefore(" (") ?: target.sourceId

    init {
        viewModelScope.launch { currentChapters = runCatching { c.repo.episodes(target.sourceId, target.entryId).size }.getOrNull() }
        search()
    }

    fun update(q: String) {
        query = q
        job?.cancel()
        job = viewModelScope.launch { delay(600); search() }
    }

    private fun search() {
        job?.cancel()
        val q = query.trim()
        val sources = c.registry.sources.filter { it.type == target.type && it.id != target.sourceId }
        candidates = emptyList(); finished = 0; total = if (q.isEmpty()) 0 else sources.size
        if (q.isEmpty()) return
        job = viewModelScope.launch {
            val sem = Semaphore(8)
            val counter = Semaphore(4)
            coroutineScope {
                sources.forEach { src ->
                    launch {
                        sem.withPermit {
                            val items = try { withTimeout(15_000) { src.search(q, 1).items.take(3) } }
                            catch (e: CancellationException) { if (e is TimeoutCancellationException) emptyList() else throw e }
                            catch (e: Exception) { emptyList() }
                            val name = src.name.substringBefore(" (")
                            val add = items.map { Candidate(it, name, titleScore(q, it.title)) }
                            candidates = (candidates + add).sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.sourceName })
                            finished++
                            // only confident matches get a (costly) chapter count
                            add.filter { it.score >= 0.5f }.forEach { cand -> launch { count(cand, counter) } }
                        }
                    }
                }
            }
        }
    }

    private fun mark(cand: Candidate, f: (Candidate) -> Candidate) {
        candidates = candidates.map { if (it.entry.sourceId == cand.entry.sourceId && it.entry.id == cand.entry.id) f(it) else it }
    }

    private suspend fun count(cand: Candidate, sem: Semaphore) {
        mark(cand) { it.copy(counting = true) }
        val n = sem.withPermit { runCatching { withTimeout(20_000) { c.repo.episodes(cand.entry.sourceId, cand.entry.id).size } }.getOrNull() }
        mark(cand) { it.copy(chapters = n, counting = false, countFailed = n == null) }
    }

    fun migrate(cand: Candidate, keepOld: Boolean, done: (MigrateResult) -> Unit) {
        if (migrating) return
        viewModelScope.launch {
            migrating = true; error = null
            try { done(c.repo.migrate(target.sourceId, target.entryId, cand.entry, keepOld)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not migrate" }
            finally { migrating = false }
        }
    }
}

@Composable
fun MigrateDialog(
    target: MigrateTarget, onDismiss: () -> Unit, onMigrated: (Entry, MigrateResult) -> Unit,
    positionLabel: String? = null,
) {
    val ink = LocalInk.current
    val vm = inkViewModel("migrate/${target.sourceId}/${target.entryId}") { MigrateViewModel(it, target) }
    var pending by remember { mutableStateOf<Candidate?>(null) }
    var keepOld by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = { if (!vm.migrating) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(ink.bg).systemBarsPadding()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(TopBarHeight).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BarIconButton(Icons.Outlined.Close, "Close") { if (!vm.migrating) onDismiss() }
                    Text("Migrate", style = InkType.headline, color = ink.label, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    positionLabel?.let { Text(it, style = InkType.callout, color = ink.secondary, modifier = Modifier.padding(end = 12.dp)) }
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    item {
                        Row(
                            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(CardRadius)).background(ink.card).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Cover(target.coverUrl, Modifier.width(44.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Moving from ${vm.sourceName}", style = InkType.caption, color = ink.accent)
                                Text(target.title, style = InkType.headline, color = ink.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                vm.currentChapters?.let { Text("$it chapters", style = InkType.callout, color = ink.secondary) }
                            }
                        }
                    }
                    item {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SearchField(vm.query, vm::update, "Search other sources")
                            if (vm.total > 0) {
                                Text(
                                    if (vm.running) "Searching ${vm.finished} of ${vm.total} sources…" else "Searched ${vm.total} sources",
                                    style = InkType.caption, color = ink.secondary,
                                )
                                if (vm.running) LinearProgressIndicator(progress = { vm.finished / vm.total.toFloat() }, modifier = Modifier.fillMaxWidth(), color = ink.accent)
                            }
                            vm.error?.let { Text(it, style = InkType.callout, color = ink.accent) }
                        }
                    }
                    if (!vm.running && vm.candidates.isEmpty() && vm.total > 0) item {
                        EmptyState(Icons.Outlined.SearchOff, "No matches in other sources.\nTry a shorter title.")
                    }
                    items(vm.candidates, key = { it.entry.sourceId + "/" + it.entry.id }) { cand ->
                        Row(
                            Modifier.fillMaxWidth().pressable { pending = cand; keepOld = false }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Cover(cand.entry.coverUrl, Modifier.width(48.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(cand.entry.title, style = InkType.headline, color = ink.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(cand.sourceName, style = InkType.callout, color = ink.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(8.dp))
                            when {
                                cand.counting -> Box(Modifier.width(64.dp).height(14.dp).clip(RoundedCornerShape(7.dp)).shimmer())
                                cand.chapters != null -> Text(
                                    "${cand.chapters} ch.", style = InkType.callout,
                                    color = if (vm.currentChapters != null && cand.chapters >= vm.currentChapters!!) ink.accent else ink.secondary,
                                )
                                cand.countFailed -> Text("?", style = InkType.callout, color = ink.secondary)
                            }
                        }
                        Hairline(startInset = 76.dp)
                    }
                }
            }
            if (vm.migrating) Box(Modifier.fillMaxSize().background(ink.bg.copy(alpha = 0.7f)).pressable {}, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ink.accent)
            }
        }
    }

    val ctx = LocalContext.current
    pending?.let { cand ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Migrate to ${cand.sourceName}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Category, reminders, read progress and history move to \"${cand.entry.title}\".",
                        style = InkType.callout, color = ink.label,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Keep old copy in library", style = InkType.body, color = ink.label, modifier = Modifier.weight(1f))
                        Switch(keepOld, { keepOld = it }, colors = SwitchDefaults.colors(checkedTrackColor = ink.accent))
                    }
                }
            },
            confirmButton = {
                TextButton({
                    val keep = keepOld; pending = null
                    vm.migrate(cand, keep) { r ->
                        android.widget.Toast.makeText(ctx, "Migrated. ${r.mappedRead} read chapters carried over.", android.widget.Toast.LENGTH_SHORT).show()
                        onMigrated(cand.entry, r)
                    }
                }) { Text("Migrate") }
            },
            dismissButton = { TextButton({ pending = null }) { Text("Cancel") } },
        )
    }
}
