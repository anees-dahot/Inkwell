# Inkwell — manga / manhwa / donghua app (Android, MVP)

## Goal
Native Android app: browse + read manga/manhwa, browse + watch donghua.
Sources are BUILT INTO the app (no extension system, no APK plugins). Pluggable
`Source` interface so new sources = new Kotlin class registered in `SourceRegistry`.
These sources are to be added, and also more better ones related to this:
manga18club mangamo mangarawclub webnovel 
BaiLianTales.ts HangulPlanet.ts NovelOnline.ts StorySeedling.ts animeAnyway.ts ao3.ts asianfanfics.ts asianhobbyist.ts asurascans.ts atsumaru.ts bakaTsuki.ts beastnovels.ts bestlightnovel.broken.ts bloodcoretl.ts boonnovel.ts borntobenovel.ts bronstl.ts cclawtranslations.ts cherrymistcafe.ts chikari.ts chrysanthemumgarden.ts cotereader.ts craneanime.ts crimsonscrolls.ts daotekno.ts decodednovels.ts divinedaolibrary.ts dragonholic.ts dreambigtl.ts dreamyTranslations.ts earlynovel.broken.ts eatapplepies.ts empirenovel.ts experimentaltranslations.ts faqwikius.ts fenrirrealm.ts fictionzone.ts firebirdsnest.ts firsttrytranslations.ts foxteller.ts fucknovelpia.ts genesis.ts hennoveltranslations.ts impatientmtlreader.ts indraTranslations.ts inkitt.ts inoveltranslation.ts isogashiineeto.ts itsametranslations.ts kakushitranslations.ts kaystranslations.ts kdtnovels.ts konkon.ts leafstudio.ts lightnovelto.ts lightnoveltranslation.ts lightnovelworld.ts lncrawler.ts lnmtl.ts lnori.ts luminaretranslations.ts lupussensei.ts machineslicedbread.ts maddertranslates.ts maehwasup.ts miumiu.ts mizupanshoko.ts mtlreader.broken.ts mvlempyr.ts mylasted.ts mynovels.ts nephologytl.ts nightjarreads.ts novel7s.ts novelarchive.ts novelarrow.ts novelbin.ts novelbuddy.ts noveldex.ts novelhall.ts novelhi.ts novelight.ts novelito.ts novelonomicon.ts novelrest.ts novelupdates.ts nyxtranslation.ts onlinereadfreenovel.ts pawread.ts penguinsquad.ts pinkmuffintl.ts procrastranslation.ts puffinFolio.ts rainofsnow.

## Stack
- Kotlin 2.x, Jetpack Compose (Material3 base but restyled Apple-like, see UI), minSdk 26, target/compileSdk 35.
- Gradle wrapper 8.13 (cached at ~/.gradle/wrapper/dists/gradle-8.13-bin), AGP 8.x compatible, version catalog `gradle/libs.versions.toml`.
- Single module `:app`, package `app.inkwell`.
- DI: manual (AppContainer) — no Hilt (keep build simple).
- Network: OkHttp + kotlinx.serialization (Retrofit optional).
- Images: Coil 3 (compose + okhttp network).
- DB: Room (library, history, chapter read progress, custom YouTube channels). KSP.
- Navigation: navigation-compose with type-safe routes.
- Video: `com.pierfrancescosoffritti.androidyoutubeplayer:core` (official IFrame API player) for YouTube.
- Glass blur: `dev.chrisbanes.haze:haze` for translucent top/bottom bars.
- SDK at ~/Library/Android/sdk (write local.properties `sdk.dir=`), JDK 17.

## Source architecture (mirror Mihon's model, simplified)
```kotlin
enum class ContentType { MANGA, VIDEO }
data class Entry(val sourceId: String, val id: String, val title: String, val coverUrl: String?, val type: ContentType)
data class EntryDetails(val entry: Entry, val description: String?, val authors: List<String>, val genres: List<String>, val status: String?)
data class Episode(val id: String, val name: String, val number: Float?, val dateMillis: Long?, val scanlator: String? = null) // chapter or episode
data class Page(val index: Int, val imageUrl: String)
interface Source {
  val id: String; val name: String; val type: ContentType; val lang: String
  suspend fun popular(page: Int): PagedResult<Entry>
  suspend fun latest(page: Int): PagedResult<Entry>
  suspend fun search(query: String, page: Int): PagedResult<Entry>
  suspend fun details(entryId: String): EntryDetails
  suspend fun episodes(entryId: String): List<Episode>
}
interface MangaSource : Source { suspend fun pages(episodeId: String): List<Page> }
interface VideoSource : Source { suspend fun videoId(episodeId: String): String } // YouTube video id
```
`SourceRegistry` holds the list; UI shows a source picker on Browse.

### MangaDexSource (api.mangadex.org, no key)
- popular: `/manga?order[followedCount]=desc&includes[]=cover_art&limit=20&offset=..&contentRating[]=safe&contentRating[]=suggestive&hasAvailableChapters=true`
- latest: `order[latestUploadedChapter]=desc`
- search: `title=`
- cover: `https://uploads.mangadex.org/covers/{mangaId}/{fileName}.256.jpg`
- chapters: `/manga/{id}/feed?translatedLanguage[]=en&order[chapter]=desc&limit=500&includes[]=scanlation_group` (paginate by offset until total), skip chapters with `externalUrl` != null.
- pages: `/at-home/server/{chapterId}` → `{baseUrl}/data/{hash}/{file}` (fallback dataSaver).
- Set User-Agent `Inkwell/0.1 (Android)`. Respect rate limit (~5 req/s): simple OkHttp interceptor with token bucket.
- Separate "language" setting default `en`.

### YouTubeDonghuaSource (no API key)
- Data from YouTube public RSS: `https://www.youtube.com/feeds/videos.xml?playlist_id=PL...` and `?channel_id=UC...` (parse Atom XML with XmlPullParser: yt:videoId, title, media:thumbnail, published).
- An "Entry" = one series = an official playlist (or a channel). Seed list in `assets/donghua_seed.json` [{title, playlistId|channelId, cover?}]. Verify every seed ID with curl (HTTP 200 + has <entry>) before shipping; drop any that fail. Find real official uploads (e.g. Tencent Video / Bilibili official donghua playlists/channels) — if unsure, ship fewer seeds rather than invented IDs.
- Users can add a channel/playlist by pasting URL (`youtube.com/playlist?list=`, `/channel/UC..`, `/@handle` → fetch page HTML and regex `"channelId":"(UC[\w-]{22})"` or `<link rel="canonical" href=".../channel/UC...">`). Stored in Room.
- popular/latest = seed+custom list; search = filter titles locally.
- episodes = RSS entries (note: RSS gives latest 15 only — show note "Showing latest uploads" + "Open full playlist in YouTube" button via intent).

## Screens (bottom tab bar: Library · Updates · Browse · History · More)
1. **Library** — grid of saved entries (cover 2:3, title below), filter chips Manga/Donghua, unread badge.
2. **Updates** — recent chapters/episodes from library entries (manual pull-to-refresh, fetch episodes, diff with stored).
3. **Browse** — source picker (segmented), Popular/Latest tabs, search field in large-title header; infinite grid.
4. **Details** — big cover header with blurred cover backdrop, title, authors, genres chips, description (expandable), Add to library button, Resume/Start button, chapter/episode list with read state.
5. **Reader** (manga) — modes: vertical webtoon (LazyColumn, default for manhwa/long-strip tag) and paged LTR/RTL (HorizontalPager). Tap center toggles translucent overlay (chapter title, page slider, prev/next chapter, mode). Pinch-zoom on pages (custom transformable with rubber-band at bounds + double-tap zoom). Save progress (chapter + page) to Room; mark read at last page. Prefetch next ~3 pages with Coil. Keep screen on.
6. **Player** (donghua) — android-youtube-player full-width, title, episode list below, next episode; mark watched when >90%. Fullscreen landscape support.
7. **History** — chronological, swipe-to-delete with undo snackbar.
8. **More** — settings: theme (system/light/dark), manga language, default reader mode, reader direction, about/legal ("Content provided by MangaDex and official YouTube uploads").

## UI / UX — Apple look + Apple motion (mandatory)
Follows Apple "Designing Fluid Interfaces" translated to Compose:
- **Look**: iOS-like. Large collapsing titles (34sp bold, letterSpacing -0.02em) that shrink into a compact centered title on scroll. Grouped inset lists (rounded 12dp cards, `systemGroupedBackground`-style colors). Colors: light bg #F2F2F7 / card #FFFFFF, dark bg #000000 / card #1C1C1E, accent systemPink #FF2D55 (single accent). Secondary text 60% alpha. Hairline separators 0.5dp inset. Corner radius 12dp cards, 8dp covers. Use system font (default). Size-specific tracking: large text negative, body 0, caption small +.
- **Materials**: top bar and bottom tab bar translucent glass via Haze (blur ~20dp + 70% surface tint), content scrolls under. No hard divider: subtle hairline only when content is under bar. Reader overlay = dark glass. Respect reduced transparency → solid.
- **Springs everywhere** (no tween for interactive things). Compose `spring(dampingRatio, stiffness)`; map Apple response → stiffness = (2π/response)². Tokens in `ui/motion/Motion.kt`:
  - `Default` damping 1.0, response 0.35 (stiffness ≈ 322)
  - `Snappy` damping 1.0, response 0.25
  - `Bouncy` damping 0.8, response 0.35 — ONLY after momentum gestures (fling/release).
  - `Sheet` damping 0.8, response 0.3.
- **Response on press-down**: every tappable card/button gets `Modifier.pressable()` that scales to 0.97 + dims on press DOWN (via interactionSource), spring back on release; no ripple (indication = null).
- **Interruptible**: use `Animatable` for gesture-driven stuff, `animateTo` from current value, pass release velocity (VelocityTracker) into animateTo(initialVelocity).
- **Momentum projection** for paged reader / sheet snapping: `project(v) = (v/1000)*0.998/(1-0.998)`; snap to nearest target of projected point.
- **Rubber-band** at zoom/pan bounds: `f(x,d)= x*d*0.55/(d+0.55*|x|)`.
- **Spatial consistency**: Details opens with shared-element cover transition (SharedTransitionLayout + `sharedElement` on cover) from grid; back reverses same path. Reader overlay slides from top/bottom and leaves the same way.
- **Haptics**: `HapticFeedbackType` on add-to-library toggle, reaching last page, long-press. Sparingly.
- **Reduced motion**: read `Settings.Global.ANIMATOR_DURATION_SCALE == 0` → replace slides/springs with short crossfades.
- Skeleton shimmer placeholders while loading (subtle), error state with retry, empty states with SF-like symbol (Material icon) + one line.
- Edge-to-edge, predictive back enabled.

## Done criteria
- `./gradlew assembleDebug` succeeds.
- App launches on emulator (API 36 arm64), Browse MangaDex shows covers, open manga → chapters → reader shows pages; Donghua source lists seeds, opens player.
