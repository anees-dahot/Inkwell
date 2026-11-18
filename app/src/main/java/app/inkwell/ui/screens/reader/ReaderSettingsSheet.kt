package app.inkwell.ui.screens.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.inkwell.data.*
import app.inkwell.ui.components.SegmentedControl
import app.inkwell.ui.motion.pressable
import app.inkwell.ui.screens.InkSwitch
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val ink = LocalInk.current
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, color = ink.secondary, style = InkType.caption, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = ink.label, style = InkType.body, modifier = Modifier.weight(1f))
        InkSwitch(checked, onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(vm: ReaderViewModel, prefs: ReaderPrefs, onDismiss: () -> Unit) {
    val ink = LocalInk.current
    val mode = vm.mode ?: ReaderViewMode.LTR
    val scale by prefs.scale.collectAsStateWithLifecycle()
    val bg by prefs.background.collectAsStateWithLifecycle()
    val tap by prefs.tapZones.collectAsStateWithLifecycle()
    val keepOn by prefs.keepScreenOn.collectAsStateWithLifecycle()
    val pageNum by prefs.pageNumber.collectAsStateWithLifecycle()
    val volume by prefs.volumeKeys.collectAsStateWithLifecycle()
    val gap by prefs.webtoonGap.collectAsStateWithLifecycle()
    val bright by prefs.brightness.collectAsStateWithLifecycle()

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = ink.bg) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Reader", color = ink.label, style = InkType.title, modifier = Modifier.padding(bottom = 8.dp))
            Section("READING MODE (THIS TITLE)") {
                SegmentedControl(ReaderViewMode.entries.map { it.label }, mode.ordinal, { vm.setTitleMode(ReaderViewMode.entries[it]) })
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Use for all titles", color = ink.accent, style = InkType.callout, modifier = Modifier.pressable { vm.setDefaultMode(mode) }.padding(vertical = 4.dp))
                    Text("Reset to default", color = ink.accent, style = InkType.callout, modifier = Modifier.pressable { vm.resetTitleMode() }.padding(vertical = 4.dp))
                }
            }
            if (mode != ReaderViewMode.WEBTOON) Section("SCALE") {
                SegmentedControl(ReaderScale.entries.map { it.label }, scale.ordinal, { prefs.setScale(ReaderScale.entries[it]) })
            }
            Section("BACKGROUND") {
                SegmentedControl(ReaderBackground.entries.map { it.label }, bg.ordinal, { prefs.setBackground(ReaderBackground.entries[it]) })
            }
            Section("TAP ZONES") {
                SegmentedControl(ReaderTapZones.entries.map { it.label }, tap.ordinal, { prefs.setTapZones(ReaderTapZones.entries[it]) })
            }
            Section("DISPLAY") {
                SwitchRow("Keep screen on", keepOn, prefs::setKeepScreenOn)
                SwitchRow("Page number overlay", pageNum, prefs::setPageNumber)
                SwitchRow("Volume keys turn pages", volume, prefs::setVolumeKeys)
                if (mode == ReaderViewMode.WEBTOON) SwitchRow("Gap between pages", gap, prefs::setWebtoonGap)
                SwitchRow("Custom brightness", bright >= 0f) { prefs.setBrightness(if (it) 0.5f else -1f) }
                if (bright >= 0f) Slider(
                    value = bright, onValueChange = { prefs.setBrightness(it.coerceAtLeast(0.01f)) }, valueRange = 0.01f..1f,
                    colors = SliderDefaults.colors(thumbColor = androidx.compose.ui.graphics.Color.White, activeTrackColor = ink.accent),
                )
            }
        }
    }
}
