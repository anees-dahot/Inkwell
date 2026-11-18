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

/** Normalized title used to merge the same series coming from different sources. */
fun titleKey(title: String): String =
    title.lowercase()
        .replace(Regex("""\(.*?\)|\[.*?]"""), " ")
        .replace(Regex("""[^\p{L}\p{N}]+"""), "")

/**
 * Queries many sources concurrently, calling [onResult] (on the caller's dispatcher) as each one
 * finishes. A failed or timed-out source reports null.
 */
suspend fun fanOut(
    sources: List<Source>,
    parallel: Int = 8,
    timeoutMs: Long = 15_000,
    op: suspend (Source) -> PagedResult<Entry>,
    onResult: (Source, PagedResult<Entry>?) -> Unit,
) {
    val sem = Semaphore(parallel)
    coroutineScope {
        sources.forEach { src ->
            launch {
                sem.withPermit {
                    val t0 = System.currentTimeMillis()
                    val r = try { withTimeout(timeoutMs) { op(src) } }
                    catch (e: kotlinx.coroutines.TimeoutCancellationException) { null }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { null }
                    if (r == null) SourceHealth.failure(src.id) else SourceHealth.success(src.id, System.currentTimeMillis() - t0)
                    onResult(src, r)
                }
            }
        }
    }
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
        else if (first.sourceId != e.sourceId) {
            val list = also.getOrPut(first.sourceId + "/" + first.id) { mutableListOf() }
            if (e.sourceId !in list) list += e.sourceId
        }
    }
    return out
}
