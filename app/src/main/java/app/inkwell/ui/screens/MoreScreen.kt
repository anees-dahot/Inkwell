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
import app.inkwell.data.ReaderDirection
import app.inkwell.data.ReaderMode
import app.inkwell.data.ThemeMode
import app.inkwell.ui.components.*
import app.inkwell.ui.motion.pressable
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

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
fun MoreScreen(hazeState: HazeState) {
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
                        Text("Inkwell 0.1", style = InkType.headline, color = ink.label)
                        Spacer(Modifier.height(4.dp))
                        Text("Content provided by MangaDex and official YouTube uploads.", style = InkType.callout, color = ink.secondary)
                    }
                }
            }
        }
        GlassTopBar("More", collapsed, hazeState, Modifier.align(Alignment.TopCenter))
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
