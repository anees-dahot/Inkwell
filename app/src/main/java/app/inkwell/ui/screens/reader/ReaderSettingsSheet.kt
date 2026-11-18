package app.inkwell.ui.screens.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
    val bright by ReaderSession.brightness.collectAsStateWithLifecycle()
    val dual by prefs.dualPage.collectAsStateWithLifecycle()
    val coverAlone by prefs.dualCoverAlone.collectAsStateWithLifecycle()
    val orient by prefs.orientation.collectAsStateWithLifecycle()
    val crop by prefs.cropBorders.collectAsStateWithLifecycle()
    val speed by prefs.autoScrollSpeed.collectAsStateWithLifecycle()

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = ink.bg) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Reader", color = ink.label, style = InkType.title, modifier = Modifier.padding(bottom = 8.dp))
            Section("READING MODE (THIS TITLE)") {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderViewMode.entries.forEach { m ->
                        val sel = m == mode
                        Text(
                            m.label, color = if (sel) ink.bg else ink.label, style = InkType.callout,
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(if (sel) ink.accent else ink.label.copy(alpha = 0.08f))
                                .pressable { vm.setTitleMode(m) }.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Use for all titles", color = ink.accent, style = InkType.callout, modifier = Modifier.pressable { vm.setDefaultMode(mode) }.padding(vertical = 4.dp))
                    Text("Reset to default", color = ink.accent, style = InkType.callout, modifier = Modifier.pressable { vm.resetTitleMode() }.padding(vertical = 4.dp))
                }
            }
            if (mode.pagedHorizontal || mode == ReaderViewMode.VERTICAL) Section("SCALE") {
                SegmentedControl(ReaderScale.entries.map { it.label }, scale.ordinal, { prefs.setScale(ReaderScale.entries[it]) })
            }
            if (mode.pagedHorizontal) Section("DUAL PAGE") {
                SegmentedControl(ReaderDualPage.entries.map { it.label }, dual.ordinal, { prefs.setDualPage(ReaderDualPage.entries[it]) })
                if (dual != ReaderDualPage.OFF) SwitchRow("Show first page alone", coverAlone, prefs::setDualCoverAlone)
            }
            Section("ORIENTATION") {
                SegmentedControl(ReaderOrientation.entries.map { it.label }, orient.ordinal, { prefs.setOrientation(ReaderOrientation.entries[it]) })
            }
            if (mode.continuousVertical || mode.continuousHorizontal) Section("AUTO-SCROLL SPEED") {
                Slider(
                    value = speed, onValueChange = prefs::setAutoScrollSpeed, valueRange = 15f..300f,
                    colors = SliderDefaults.colors(thumbColor = androidx.compose.ui.graphics.Color.White, activeTrackColor = ink.accent),
                )
            }
            Section("BACKGROUND") {
                SegmentedControl(ReaderBackground.entries.map { it.label }, bg.ordinal, { prefs.setBackground(ReaderBackground.entries[it]) })
            }
            Section("TAP ZONES") {
                SegmentedControl(ReaderTapZones.entries.map { it.label }, tap.ordinal, { prefs.setTapZones(ReaderTapZones.entries[it]) })
            }
            Section("DISPLAY") {
                SwitchRow("Keep screen on", keepOn, prefs::setKeepScreenOn)
                if (!mode.continuousVertical) SwitchRow("Crop borders", crop, prefs::setCropBorders)
                SwitchRow("Page number overlay", pageNum, prefs::setPageNumber)
                SwitchRow("Volume keys turn pages", volume, prefs::setVolumeKeys)
                if (mode == ReaderViewMode.WEBTOON || mode.continuousHorizontal) SwitchRow("Gap between pages", gap, prefs::setWebtoonGap)
                SwitchRow("Custom brightness", bright >= 0f) { ReaderSession.brightness.value = if (it) 0.5f else -1f }
                if (bright >= 0f) Slider(
                    value = bright, onValueChange = { ReaderSession.brightness.value = it.coerceAtLeast(0.01f) }, valueRange = 0.01f..1f,
                    colors = SliderDefaults.colors(thumbColor = androidx.compose.ui.graphics.Color.White, activeTrackColor = ink.accent),
                )
            }
        }
    }
}
