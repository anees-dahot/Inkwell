package app.inkwell.data.source

import app.inkwell.data.model.Entry
import app.inkwell.data.model.PagedResult
import app.inkwell.data.model.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

private val BRACKETS = Regex("""\(.*?\)|\[.*?\]|\{.*?\}""")
private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")
private val MARKS = Regex("""\p{Mn}+""")
private val SEPARATORS = Regex("""[:\-–—|]+""")
private val SPACES = Regex("""\s+""")
private val LEADING_ARTICLE = Regex("""^(the|a|an)\s+""")
private val TRAILING_NOISE = Regex("""\s+(manga|manhwa|manhua|webtoon|comic|comics|novel|light novel|web novel|official|colored|remastered)$""")

/** Normalized title used to merge the same series coming from different sources. */
fun titleKey(title: String): String {
    var t = java.text.Normalizer.normalize(title.lowercase(), java.text.Normalizer.Form.NFKD)
        .replace(MARKS, "") // strip accents
        .replace(BRACKETS, " ")
        .replace('’', '\'').replace("&", " and ")
        .replace(SEPARATORS, " ")
        .replace(SPACES, " ").trim()
    t = t.replace(LEADING_ARTICLE, "")
    repeat(2) { t = t.replace(TRAILING_NOISE, "") }
    return t.replace(NON_ALNUM, "")
}

/** Loose match used when looking a title up on other sources. */
fun sameTitle(a: String, b: String): Boolean {
    val ka = titleKey(a); val kb = titleKey(b)
    if (ka.isEmpty() || kb.isEmpty()) return false
    if (ka == kb) return true
    val (short, long) = if (ka.length <= kb.length) ka to kb else kb to ka
    return short.length >= 6 && long.startsWith(short) && long.length - short.length <= 12
}

/**
 * Queries many sources concurrently, calling [onResult] (on the caller's dispatcher) as each one
 * finishes. A failed or timed-out source reports null.
 */
suspend fun fanOut(
    sources: List<Source>,
    parallel: Int = 6,
    timeoutMs: Long? = null,
    op: suspend (Source) -> PagedResult<Entry>,
    onResult: (Source, PagedResult<Entry>?) -> Unit,
) {
    // Adapts while running: once requests start timing out with nothing answering, the link is
    // treated as slow — fewer requests at a time, longer timeouts — and the sources that timed
    // out in fast mode get a second, patient try at the end.
    val sem = Semaphore(parallel)
    val slowGate = Semaphore(2)
    val failed = java.util.Collections.synchronizedList(ArrayList<String>())
    val retry = java.util.Collections.synchronizedList(ArrayList<Source>())
    var ok = 0
    var timeouts = 0

    suspend fun attempt(src: Source, slowMode: Boolean, last: Boolean) {
        val t0 = System.currentTimeMillis()
        val limit = timeoutMs ?: if (slowMode) 40_000L else 15_000L
        var timedOut = false
        val r = try { withTimeout(limit) { op(src) } }
        catch (e: kotlinx.coroutines.TimeoutCancellationException) { timedOut = true; null }
        catch (e: CancellationException) { throw e }
        catch (e: java.io.InterruptedIOException) { timedOut = true; null }
        catch (e: Exception) { null }
        if (r != null) {
            ok++; SourceHealth.success(src.id, System.currentTimeMillis() - t0); onResult(src, r)
            return
        }
        if (timedOut) {
            timeouts++
            if (ok == 0 && timeouts >= 3) Net.markSlow()
            if (!slowMode && !last) { retry += src; return } // decided after the first pass
        }
        failed += src.id
        onResult(src, null)
    }

    coroutineScope {
        sources.forEach { src ->
            launch {
                sem.withPermit {
                    if (Net.slow) slowGate.withPermit { attempt(src, slowMode = true, last = false) }
                    else attempt(src, slowMode = false, last = false)
                }
            }
        }
    }
    val again = retry.toList()
    if (again.isNotEmpty()) {
        if (Net.slow) coroutineScope {
            again.forEach { src -> launch { slowGate.withPermit { attempt(src, slowMode = true, last = true) } } }
        } else again.forEach { failed += it.id; onResult(it, null) }
    }
    // Only blame sources when the connection evidently works (others answered quickly); a dead or
    // very slow link would otherwise bench every source at once.
    if (ok > 0 && Net.online && !Net.slow) failed.forEach { SourceHealth.failure(it) }
}

/** Round-robin merge of per-source batches (in [order]), dropping titles already in [seen]. */
fun interleave(order: List<String>, batches: Map<String, List<Entry>>, seen: MutableMap<String, Entry>, also: MutableMap<String, MutableList<String>>): List<Entry> {
    val lists = order.mapNotNull { batches[it] }
    val out = ArrayList<Entry>()
    val max = lists.maxOfOrNull { it.size } ?: 0
    for (i in 0 until max) for (l in lists) {
        val e = l.getOrNull(i) ?: continue
        val k = titleKey(e.title).ifEmpty { e.sourceId + e.id }
        val first = seen[k]
        if (first == null) { seen[k] = e; out += e }
        else if (e.latest != null && first.latest == null) {
            // the card's own source didn't list a chapter number: borrow one from a duplicate
            val idx = out.indexOf(first)
            if (idx >= 0) { val up = first.copy(latest = e.latest); out[idx] = up; seen[k] = up }
        }
        if (first != null && first.sourceId != e.sourceId) {
            val list = also.getOrPut(first.sourceId + "/" + first.id) { mutableListOf() }
            if (e.sourceId !in list) list += e.sourceId
        }
    }
    return out
}
