# Inkwell

Android app for reading **manga, manhwa and web novels** and watching **donghua**, with all sources built in — no extensions to install.

## Features

- **90+ built-in sources** — MangaDex and MangaRawClub for manga, 89 web-novel sources (ported from LNReader plugins), and YouTube channels for donghua
- **Searchable source picker** with recently used sources
- **Library** with unread badges and a "Continue reading" card
- **Background library updates** (every 6/12/24 h, Wi-Fi / charging only) with new-chapter notifications
- **Upcoming release calendar** — predicts each followed title's next chapter from its release history
- **Reminders** — per-title bell for new chapters plus custom weekly reading reminders
- **Cloudflare support** — sites behind a Cloudflare check can be unlocked once in the built-in WebView
- **Backup & restore** — library, progress, history and reminders as a JSON file
- Manga reader, novel reader, history, light/dark theme

## Install

Download the latest APK from [Releases](../../releases) and open it on your phone (allow installing from unknown sources). Requires Android 8.0+.

## Build

```bash
./gradlew :app:assembleDebug
```

Requires JDK 17 and the Android SDK (`local.properties` → `sdk.dir=...`).

Live source check (hits the real sites, writes `app/build/smoke-report.tsv`):

```bash
./gradlew :app:testDebugUnitTest -Psmoke --tests app.inkwell.SourceSmokeTest
# subset: -Ponly=novelbin,webnovel
```

Adding a source: see [PORTING.md](PORTING.md).

## Disclaimer

Inkwell does not host any content. All titles are fetched from third-party websites; their availability and legality are the responsibility of those sites. Support the official releases where you can.
