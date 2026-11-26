package app.inkwell.data.source

import android.content.Context
import android.content.SharedPreferences
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Image CDNs often reject hotlinks without the site's Referer. Sources register
 * the host of each image url they return; the shared image loader adds the header.
 * Persisted so covers stored in the library still load after an app restart.
 */
object ImageReferers {
    private val byHost = ConcurrentHashMap<String, String>()
    private var sp: SharedPreferences? = null

    fun init(context: Context) {
        val p = context.getSharedPreferences("image_referers", Context.MODE_PRIVATE)
        sp = p
        p.all.forEach { (k, v) -> (v as? String)?.let { byHost.putIfAbsent(k, it) } }
    }

    fun register(imageUrl: String?, referer: String): String? {
        val host = imageUrl?.toHttpUrlOrNull()?.host ?: return imageUrl
        if (byHost.putIfAbsent(host, referer) == null) sp?.edit()?.putString(host, referer)?.apply()
        return imageUrl
    }

    fun forHost(host: String): String? = byHost[host]
}
