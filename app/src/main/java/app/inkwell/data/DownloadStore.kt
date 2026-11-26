package app.inkwell.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** On-disk layout for offline chapters: root/<hash(source/entry)>/<hash(episode)>/{text.json | 0001.img ...}. */
class DownloadStore(private val root: File) {
    private val json = Json

    private fun h(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun entryDir(s: String, e: String) = File(root, h("$s/$e"))
    fun dir(s: String, e: String, ep: String) = File(entryDir(s, e), h(ep))
    fun tmpDir(s: String, e: String, ep: String) = File(entryDir(s, e), h(ep) + ".tmp")

    fun pageFile(dir: File, index: Int) = File(dir, "%04d.img".format(index))

    fun pageFiles(s: String, e: String, ep: String): List<File> =
        dir(s, e, ep).listFiles { f -> f.name.endsWith(".img") }?.sortedBy { it.name } ?: emptyList()

    fun readText(s: String, e: String, ep: String): List<String>? {
        val f = File(dir(s, e, ep), "text.json")
        if (!f.isFile) return null
        return runCatching { json.decodeFromString(ListSerializer(String.serializer()), f.readText()) }.getOrNull()
    }

    fun writeText(dir: File, paragraphs: List<String>): Long {
        val txt = json.encodeToString(ListSerializer(String.serializer()), paragraphs)
        File(dir, "text.json").writeText(txt)
        return txt.toByteArray().size.toLong()
    }

    fun has(s: String, e: String, ep: String): Boolean {
        val d = dir(s, e, ep)
        return d.isDirectory && (File(d, "text.json").isFile || pageFiles(s, e, ep).isNotEmpty())
    }

    fun delete(s: String, e: String, ep: String) {
        dir(s, e, ep).deleteRecursively(); tmpDir(s, e, ep).deleteRecursively()
        entryDir(s, e).takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
    }

    fun deleteEntry(s: String, e: String) { entryDir(s, e).deleteRecursively() }

    fun sizeOf(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Removes leftover .tmp dirs from interrupted downloads. */
    /** Partial chapters are kept for resuming after a dropped connection; stale ones (2+ days) are removed. */
    fun cleanTemp(maxAgeMs: Long = 2L * 24 * 60 * 60 * 1000) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        root.walkTopDown().filter { it.isDirectory && it.name.endsWith(".tmp") && it.lastModified() <= cutoff }.toList().forEach { it.deleteRecursively() }
    }
}
