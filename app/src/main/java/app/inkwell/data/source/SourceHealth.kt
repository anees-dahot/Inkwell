package app.inkwell.data.source

import android.content.Context
import android.content.SharedPreferences
import app.inkwell.data.model.Entry
import app.inkwell.data.model.PagedResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks how each source behaves in the combined feed so dead or failing sites stop slowing
 * everything down. A source that fails [FAIL_LIMIT] times in a row is benched for [COOLDOWN_MS],
 * then gets one more try. Speed (EMA of response time) decides order among the rest.
 */
object SourceHealth {
    private const val FAIL_LIMIT = 3
    private const val COOLDOWN_MS = 6 * 60 * 60 * 1000L

    private data class Stat(val fails: Int = 0, val lastFail: Long = 0, val avgMs: Long = 0)

    private var sp: SharedPreferences? = null
    private val stats = ConcurrentHashMap<String, Stat>()

    fun init(context: Context) {
        val p = context.getSharedPreferences("source_health", Context.MODE_PRIVATE)
        sp = p
        p.all.forEach { (k, v) ->
            val parts = (v as? String)?.split(",") ?: return@forEach
            if (parts.size == 3) stats[k] = Stat(parts[0].toInt(), parts[1].toLong(), parts[2].toLong())
        }
    }

    private fun save(id: String, s: Stat) {
        stats[id] = s
        sp?.edit()?.putString(id, "${s.fails},${s.lastFail},${s.avgMs}")?.apply()
    }

    fun success(id: String, ms: Long) {
        val s = stats[id] ?: Stat()
        val avg = if (s.avgMs == 0L) ms else (s.avgMs * 7 + ms) / 8
        save(id, Stat(0, 0, avg))
    }

    fun failure(id: String) {
        val s = stats[id] ?: Stat()
        save(id, s.copy(fails = s.fails + 1, lastFail = System.currentTimeMillis(), avgMs = maxOf(s.avgMs, 15_000)))
    }

    /** True when the source should be skipped in combined feeds right now. */
    fun benched(id: String): Boolean {
        val s = stats[id] ?: return false
        return s.fails >= FAIL_LIMIT && System.currentTimeMillis() - s.lastFail < COOLDOWN_MS
    }

    fun avgMs(id: String): Long = stats[id]?.avgMs?.takeIf { it > 0 } ?: 4_000

    fun resetAll() {
        stats.clear(); sp?.edit()?.clear()?.apply()
    }
}

/** Short-lived in-memory cache of listing/search pages so tab switches and back navigation are instant. */
object ResultCache {
    private const val TTL_MS = 10 * 60 * 1000L
    private const val MAX = 400
    private data class Hit(val at: Long, val r: PagedResult<Entry>)
    private val map = object : LinkedHashMap<String, Hit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Hit>?) = size > MAX
    }

    @Synchronized fun get(key: String): PagedResult<Entry>? {
        val h = map[key] ?: return null
        if (System.currentTimeMillis() - h.at > TTL_MS) { map.remove(key); return null }
        return h.r
    }

    @Synchronized fun put(key: String, r: PagedResult<Entry>) { map[key] = Hit(System.currentTimeMillis(), r) }

    @Synchronized fun clear() = map.clear()

    suspend fun cached(key: String, force: Boolean = false, load: suspend () -> PagedResult<Entry>): PagedResult<Entry> {
        if (!force) get(key)?.let { return it }
        return load().also { if (it.items.isNotEmpty()) put(key, it) }
    }
}
