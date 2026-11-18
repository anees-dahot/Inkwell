# Porting LNReader plugins -> Inkwell (Kotlin)

Originals: https://github.com/LNReader/lnreader-plugins (`plugins/english/*.ts`, some under `plugins/multi/`). Fetch raw via
`curl -sL https://raw.githubusercontent.com/LNReader/lnreader-plugins/master/plugins/english/<name>.ts` (find path via GitHub API search if 404).
Many plugins are generated from templates (`src/plugins/english/` + `multisrc/` like madara, lightnovelwp, readnovelfull, fictioneer, hotnovelpub) —
if a plugin is a template config, port the template logic for that site.

Rules:
- One file per site: `app/src/main/java/app/inkwell/data/source/novel/<Name>Source.kt`, package `app.inkwell.data.source.novel`.
- `class XSource(client: OkHttpClient) : ScraperNovelSource(client)`; override id (plugin id), name, baseUrl, lang;
  implement popular, latest, search, details, episodes, chapterText (see `data/model/Models.kt`, `data/source/ScraperSource.kt`).
- Use base helpers: fetch/doc/json/jsonPost/formPost/abs/absPath/parseDate, `paragraphsOf(el)`, protected `json` (kotlinx Json).
- Import what you use explicitly (e.g. `kotlinx.serialization.json.jsonPrimitive`). No file-private helpers shared across files — keep helpers private inside the class.
- Good references: novel/NovelRestSource.kt (JSON API), novel/NovelBinSource.kt.
- Do NOT edit other agents' files, ScraperSource.kt, NovelSources.kt or AppContainer.kt. Report class names; main thread registers them.
- Compile: `./gradlew :app:compileDebugKotlin -q 2>&1 | grep '^e:' | grep -E '<YourFile1>|<YourFile2>'` — other files may have unrelated errors; only yours must be clean.
- If a site is dead / plugin removed / needs login-only or Cloudflare-only JS, skip it and say why.
