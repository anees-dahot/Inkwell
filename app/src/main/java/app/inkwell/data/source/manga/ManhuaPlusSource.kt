package app.inkwell.data.source.manga

import okhttp3.OkHttpClient

/** manhuaplus.com — Madara theme. */
class ManhuaPlusSource(client: OkHttpClient) : MadaraSource(client) {
    override val baseUrl = "https://manhuaplus.com"
    override val id = "manhuaplus"
    override val name = "ManhuaPlus"
}
