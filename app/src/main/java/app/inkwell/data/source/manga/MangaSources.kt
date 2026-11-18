package app.inkwell.data.source.manga

import app.inkwell.data.model.Source
import okhttp3.OkHttpClient

/** Mangamo skipped: Firebase/RevenueCat-authenticated paid app API. */
fun mangaSources(client: OkHttpClient): List<Source> = listOf(
    // Manga18ClubSource(client), // domain dead (DNS SERVFAIL, 2026-11)
    MangaRawClubSource(client),
    MangapillSource(client),
    WeebCentralSource(client),
    AsuraScansMangaSource(client),
    FlameComicsSource(client),
    MangaReadSource(client),
    MangaKatanaSource(client),
    NatomangaSource(client),
    WebtoonsSource(client),
    MangaPlusSource(client),
    ManhuaPlusSource(client),
    KingOfShojoSource(client),
    RizzFablesSource(client),
    VortexScansSource(client),
    MangaHereSource(client),
    FanfoxSource(client),
    MangaTownSource(client),
    MangaFreakSource(client),
    KatamangaSource(client),
    AtsuMoeSource(client),
    ComickSource(client),
    ManhwaClubSource(client),
    MangaForFreeSource(client),
    MangaDistrictSource(client),
)
