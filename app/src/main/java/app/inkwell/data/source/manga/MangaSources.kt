package app.inkwell.data.source.manga

import app.inkwell.data.model.Source
import okhttp3.OkHttpClient

/** Mangamo skipped: Firebase/RevenueCat-authenticated paid app API. */
fun mangaSources(client: OkHttpClient): List<Source> = listOf(
    // Manga18ClubSource(client), // domain dead (DNS SERVFAIL, 2026-11)
    MangaRawClubSource(client),
)
