package app.inkwell.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import app.inkwell.AppLock
import app.inkwell.data.AccentColor
import app.inkwell.data.ReaderDirection
import app.inkwell.data.ReaderMode
import app.inkwell.data.ThemeMode
import app.inkwell.ui.components.*
import app.inkwell.ui.motion.pressable
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch

private val LANGS = listOf(
    "en" to "English", "es" to "Spanish", "es-la" to "Spanish (LATAM)", "fr" to "French", "pt-br" to "Portuguese (BR)",
    "id" to "Indonesian", "ru" to "Russian", "it" to "Italian", "de" to "German", "ja" to "Japanese", "ko" to "Korean",
)

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, style = InkType.callout, color = LocalInk.current.secondary)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = InkType.body, color = LocalInk.current.label, modifier = Modifier.weight(1f))
        InkSwitch(checked, onChange)
    }
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    Text(
        label, style = InkType.body, color = LocalInk.current.accent,
        modifier = Modifier.fillMaxWidth().pressable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

@Composable
fun MoreScreen(hazeState: HazeState, onOpenUpcoming: () -> Unit = {}, onOpenDownloads: () -> Unit = {}, onOpenStats: () -> Unit = {}) {
    val c = LocalContainer.current
    val s = c.settings
    val theme by s.theme.collectAsStateWithLifecycle()
    val lang by s.lang.collectAsStateWithLifecycle()
    val mode by s.readerMode.collectAsStateWithLifecycle()
    val dir by s.readerDirection.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val collapsed by list.rememberCollapsed()
    val ink = LocalInk.current
    var langDialog by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val hours by s.updateHours.collectAsStateWithLifecycle()
    val wifiOnly by s.updateWifiOnly.collectAsStateWithLifecycle()
    val charging by s.updateCharging.collectAsStateWithLifecycle()
    val dlWifi by s.downloadWifiOnly.collectAsStateWithLifecycle()
    val dlAuto by s.autoDownload.collectAsStateWithLifecycle()
    val askNotif = rememberNotificationPermission()
    val accent by s.accent.collectAsStateWithLifecycle()
    val amoled by s.amoled.collectAsStateWithLifecycle()
    val dynamic by s.dynamicColor.collectAsStateWithLifecycle()
    val lockMin by s.lockMinutes.collectAsStateWithLifecycle()
    val secure by s.secureScreen.collectAsStateWithLifecycle()
    val incognito by s.incognito.collectAsStateWithLifecycle()
    val showAdult by s.showAdult.collectAsStateWithLifecycle()
    val lockOptions = listOf(-1, 0, 1, 5, 15)
    val canDynamic = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    var message by remember { mutableStateOf<String?>(null) }
    val intervals = listOf(0, 6, 12, 24)
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) scope.launch {
            message = runCatching {
                val text = c.repo.exportBackup()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
                }
                "Backup saved."
            }.getOrElse { "Export failed: ${it.message}" }
        }
    }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            message = runCatching {
                val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) }
                }
                val r = c.repo.importBackup(text)
                app.inkwell.work.Scheduler.syncAll(ctx)
                scope.launch { c.repo.refreshLibrary() }
                "Restored ${r.library} titles, ${r.categories} categories, ${r.progress} progress entries, ${r.history} history items, ${r.reminders} reminders."
            }.getOrElse { "Import failed: ${it.message}" }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().hazeSource(hazeState),
            contentPadding = PaddingValues(top = topContentPadding(), bottom = LocalBottomInset.current.calculateBottomPadding() + 24.dp),
        ) {
            item { LargeTitle("More") }
            item { SectionHeader("Appearance") }
            item {
                GroupedCard {
                    SettingRow("Theme") {
                        SegmentedControl(listOf("System", "Light", "Dark"), theme.ordinal, { s.setTheme(ThemeMode.entries[it]) })
                    }
                    Hairline()
                    SettingRow(if (dynamic && canDynamic) "Accent color (using system colors)" else "Accent color") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            AccentColor.entries.forEach { a ->
                                Box(
                                    Modifier.size(30.dp).clip(CircleShape).background(Color(a.argb)).pressable { s.setAccent(a) },
                                    contentAlignment = Alignment.Center,
                                ) { if (a == accent) Icon(Icons.Filled.Check, a.label, tint = Color.White, modifier = Modifier.size(18.dp)) }
                            }
                        }
                    }
                    Hairline()
                    ToggleRow("AMOLED black (dark mode)", amoled) { s.setAmoled(it) }
                    if (canDynamic) {
                        Hairline()
                        ToggleRow("Dynamic color (Material You)", dynamic) { s.setDynamicColor(it) }
                    }
                }
            }
            item { SectionHeader("Privacy") }
            item {
                GroupedCard {
                    ToggleRow("Incognito mode", incognito) { s.setIncognito(it) }
                    Hairline()
                    ToggleRow("Show 18+ sources and titles", showAdult) { s.setShowAdult(it); app.inkwell.data.source.ResultCache.clear() }
                    Hairline()
                    SettingRow("App lock") {
                        SegmentedControl(listOf("Off", "Instantly", "1 min", "5 min", "15 min"), lockOptions.indexOf(lockMin).coerceAtLeast(0), {
                            val v = lockOptions[it]
                            if (v >= 0 && !AppLock.available(ctx)) message = "Set up a screen lock or biometrics in your device settings first."
                            else s.setLockMinutes(v)
                        })
                    }
                    Hairline()
                    ToggleRow("Hide in recent apps & screenshots", secure) { s.setSecureScreen(it) }
                }
            }
            item {
                Text(
                    "Incognito: reading progress, history and statistics are not recorded. App lock asks for fingerprint, face or your device PIN after the chosen time in the background.",
                    style = InkType.caption, color = ink.secondary, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )
            }
            item { SectionHeader("Reading") }
            item { GroupedCard { ActionRow("Reading statistics", onClick = onOpenStats) } }
            item { SectionHeader("Library updates") }
            item {
                GroupedCard {
                    SettingRow("Check for new chapters") {
                        SegmentedControl(listOf("Off", "6 hours", "12 hours", "24 hours"), intervals.indexOf(hours).coerceAtLeast(0), {
                            s.setUpdateHours(intervals[it])
                            if (intervals[it] > 0) askNotif()
                            app.inkwell.work.Scheduler.syncPeriodic(ctx, s)
                        })
                    }
                    Hairline()
                    ToggleRow("Only on Wi-Fi", wifiOnly) { s.setUpdateWifiOnly(it); app.inkwell.work.Scheduler.syncPeriodic(ctx, s) }
                    Hairline()
                    ToggleRow("Only while charging", charging) { s.setUpdateCharging(it); app.inkwell.work.Scheduler.syncPeriodic(ctx, s) }
                    Hairline()
                    ActionRow("Update library now") { askNotif(); app.inkwell.work.Scheduler.updateNow(ctx); message = "Updating library…" }
                    Hairline()
                    ActionRow("Upcoming releases & reminders", onClick = onOpenUpcoming)
                }
            }
            item { SectionHeader("Downloads") }
            item {
                GroupedCard {
                    ActionRow("Downloads & queue", onClick = onOpenDownloads)
                    Hairline()
                    ToggleRow("Download only on Wi-Fi", dlWifi) {
                        s.setDownloadWifiOnly(it)
                        scope.launch { app.inkwell.work.Downloads.restartIfPending(ctx) }
                    }
                    Hairline()
                    ToggleRow("Auto-download new chapters", dlAuto) { s.setAutoDownload(it); if (it) askNotif() }
                }
            }
            item { SectionHeader("Backup") }
            item {
                GroupedCard {
                    ActionRow("Export backup") { app.inkwell.AppLock.skipNextLock = true; exporter.launch("inkwell-backup.json") }
                    Hairline()
                    ActionRow("Restore from backup") { app.inkwell.AppLock.skipNextLock = true; importer.launch(arrayOf("application/json", "*/*")) }
                }
            }
            item { SectionHeader("Manga") }
            item {
                GroupedCard {
                    Row(
                        Modifier.fillMaxWidth().pressable { langDialog = true }.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Chapter language", style = InkType.body, color = ink.label, modifier = Modifier.weight(1f))
                        Text(LANGS.firstOrNull { it.first == lang }?.second ?: lang, style = InkType.body, color = ink.secondary)
                    }
                    Hairline()
                    SettingRow("Default reader mode") {
                        SegmentedControl(listOf("Auto", "Webtoon", "Paged"), mode.ordinal, { s.setReaderMode(ReaderMode.entries[it]) })
                    }
                    Hairline()
                    SettingRow("Paged direction") {
                        SegmentedControl(listOf("Left to right", "Right to left"), dir.ordinal, { s.setReaderDirection(ReaderDirection.entries[it]) })
                    }
                }
            }
            item { SectionHeader("About") }
            item {
                GroupedCard {
                    Column(Modifier.padding(16.dp)) {
                        Text("Inkwell ${app.inkwell.BuildConfig.VERSION_NAME}", style = InkType.headline, color = ink.label)
                        Spacer(Modifier.height(4.dp))
                        Text("Content provided by MangaDex and official YouTube uploads.", style = InkType.callout, color = ink.secondary)
                    }
                }
            }
            item { SectionHeader("Updates") }
            item {
                val updater = LocalContainer.current.updater
                val st by updater.state.collectAsStateWithLifecycle()
                val auto by updater.autoCheck.collectAsStateWithLifecycle()
                val status = when (val u = st) {
                    is app.inkwell.data.UpdateState.Idle -> "Tap to check for a new version"
                    is app.inkwell.data.UpdateState.Checking -> "Checking..."
                    is app.inkwell.data.UpdateState.UpToDate -> "You're on the latest version"
                    is app.inkwell.data.UpdateState.Available -> "Version ${u.info.version} available"
                    is app.inkwell.data.UpdateState.Downloading -> "Downloading ${(u.progress * 100).toInt()}%"
                    is app.inkwell.data.UpdateState.ReadyToInstall -> "Ready to install"
                    is app.inkwell.data.UpdateState.Error -> u.message
                }
                GroupedCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("Version ${app.inkwell.BuildConfig.VERSION_NAME}", style = InkType.body, color = ink.label)
                        Text(status, style = InkType.caption, color = ink.secondary)
                    }
                    Hairline()
                    ActionRow(if (st is app.inkwell.data.UpdateState.Available) "View update" else "Check for updates") {
                        if (st is app.inkwell.data.UpdateState.Available || st is app.inkwell.data.UpdateState.ReadyToInstall) updater.showDialog()
                        else updater.check()
                    }
                    Hairline()
                    ToggleRow("Check for updates automatically", auto) { updater.setAutoCheck(it) }
                }
            }
        }
        GlassTopBar("More", collapsed, hazeState, Modifier.align(Alignment.TopCenter))
    }

    message?.let { m ->
        AlertDialog(
            onDismissRequest = { message = null }, text = { Text(m) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }

    if (langDialog) AlertDialog(
        onDismissRequest = { langDialog = false },
        title = { Text("Chapter language") },
        text = {
            Column {
                LANGS.forEach { (code, name) ->
                    Row(
                        Modifier.fillMaxWidth().pressable { s.setLang(code); langDialog = false }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, modifier = Modifier.weight(1f), color = if (code == lang) ink.accent else ink.label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { langDialog = false }) { Text("Close") } },
    )
}
