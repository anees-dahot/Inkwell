package app.inkwell.data.source

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Image CDNs often reject hotlinks without the site's Referer. Sources register
 * the host of each image url they return; the shared image loader adds the header.
 */
object ImageReferers {
    private val byHost = ConcurrentHashMap<String, String>()

    fun register(imageUrl: String?, referer: String): String? {
        imageUrl?.toHttpUrlOrNull()?.host?.let { byHost.putIfAbsent(it, referer) }
        return imageUrl
    }

    fun forHost(host: String): String? = byHost[host]
}
