package app.inkwell.ui.screens.novel

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.inkwell.data.NovelFont
import app.inkwell.data.NovelPrefs
import app.inkwell.data.NovelTheme
import app.inkwell.ui.components.SegmentedControl
import app.inkwell.ui.motion.pressable
import app.inkwell.ui.theme.InkColors
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk
import java.util.Locale

class NovelPalette(val bg: Color, val text: Color, val secondary: Color, val dark: Boolean)

fun novelPalette(theme: NovelTheme, ink: InkColors): NovelPalette = when (theme) {
    NovelTheme.FOLLOW -> NovelPalette(ink.bg, ink.label, ink.secondary, ink.dark)
    NovelTheme.LIGHT -> NovelPalette(Color(0xFFFFFFFF), Color(0xFF1C1C1E), Color(0x991C1C1E), false)
    NovelTheme.SEPIA -> NovelPalette(Color(0xFFF4ECD8), Color(0xFF5B4636), Color(0x995B4636), false)
    NovelTheme.DARK -> NovelPalette(Color(0xFF1E1F22), Color(0xFFD2D2D6), Color(0x99D2D2D6), true)
    NovelTheme.BLACK -> NovelPalette(Color.Black, Color(0xFFB4B4B8), Color(0x99B4B4B8), true)
}

private val fontCache = HashMap<NovelFont, FontFamily>()
fun novelFontFamily(f: NovelFont): FontFamily = fontCache.getOrPut(f) {
    runCatching { FontFamily(Typeface.create(f.sys, Typeface.NORMAL)) }.getOrDefault(FontFamily.Default)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelSettingsSheet(prefs: NovelPrefs, onChange: ((NovelPrefs) -> NovelPrefs) -> Unit, onDismiss: () -> Unit) {
    val ink = LocalInk.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ink.bg,
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.85f).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Reading", style = InkType.headline, color = ink.label)

            // Theme swatches
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NovelTheme.entries.forEach { t ->
                    val p = novelPalette(t, ink)
                    val sel = prefs.theme == t
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(46.dp).clip(CircleShape).background(p.bg)
                                .border(if (sel) 2.5.dp else 1.dp, if (sel) ink.accent else ink.separator, CircleShape)
                                .pressable { onChange { it.copy(theme = t) } },
                            contentAlignment = Alignment.Center,
                        ) { Text("Aa", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                        Spacer(Modifier.height(4.dp))
                        Text(t.label, style = InkType.caption, color = if (sel) ink.label else ink.secondary)
                    }
                }
            }

            SliderRow("Font size", "${prefs.fontSize.toInt()}", prefs.fontSize, 12f..32f, 19) { v -> onChange { it.copy(fontSize = v) } }
            SliderRow("Line spacing", String.format(Locale.US, "%.1f", prefs.lineSpacing), prefs.lineSpacing, 1.0f..2.4f, 13) { v -> onChange { it.copy(lineSpacing = (Math.round(v * 10) / 10f)) } }
            SliderRow("Paragraph spacing", "${prefs.paraSpacing.toInt()}", prefs.paraSpacing, 0f..40f, 19) { v -> onChange { it.copy(paraSpacing = v) } }
            SliderRow("Side margins", "${prefs.margin.toInt()}", prefs.margin, 0f..48f, 23) { v -> onChange { it.copy(margin = v) } }

            Label("Alignment")
            SegmentedControl(listOf("Left", "Justify"), if (prefs.justify) 1 else 0, { i -> onChange { it.copy(justify = i == 1) } })

            Label("Font")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NovelFont.entries.forEach { f ->
                    val sel = prefs.font == f
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).background(if (sel) ink.accent else ink.card)
                            .pressable { onChange { it.copy(font = f) } }.padding(horizontal = 14.dp, vertical = 8.dp),
                    ) { Text(f.label, color = if (sel) Color.White else ink.label, fontSize = 15.sp, fontFamily = novelFontFamily(f)) }
                }
            }

            Label("Behavior")
            ToggleCard(listOf(
                Triple("Keep screen on", prefs.keepScreenOn) { v: Boolean -> onChange { it.copy(keepScreenOn = v) } },
                Triple("Volume keys scroll", prefs.volumeKeys) { v: Boolean -> onChange { it.copy(volumeKeys = v) } },
                Triple("Tap zones scroll", prefs.tapZones) { v: Boolean -> onChange { it.copy(tapZones = v) } },
                Triple("Continuous scroll", prefs.continuous) { v: Boolean -> onChange { it.copy(continuous = v) } },
            ))

            Label("Listen")
            SliderRow("Speech speed", String.format(Locale.US, "%.2fx", prefs.ttsRate), prefs.ttsRate, 0.5f..2.5f, 19) { v -> onChange { it.copy(ttsRate = Math.round(v * 20) / 20f) } }
            SliderRow("Pitch", String.format(Locale.US, "%.1f", prefs.ttsPitch), prefs.ttsPitch, 0.5f..2f, 14) { v -> onChange { it.copy(ttsPitch = Math.round(v * 10) / 10f) } }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Label(t: String) {
    Text(t, style = InkType.caption.copy(fontWeight = FontWeight.SemiBold), color = LocalInk.current.secondary)
}

@Composable
private fun SliderRow(title: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onValue: (Float) -> Unit) {
    val ink = LocalInk.current
    // Local state keeps the thumb smooth; value is committed on every change (cheap SharedPreferences write).
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = InkType.callout, color = ink.label, modifier = Modifier.weight(1f))
            Text(value, style = InkType.callout, color = ink.secondary)
        }
        Slider(
            value = v, onValueChange = onValue, valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = ink.accent, activeTrackColor = ink.accent, inactiveTrackColor = ink.secondary.copy(alpha = 0.2f),
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
        )
    }
}

@Composable
private fun ToggleCard(items: List<Triple<String, Boolean, (Boolean) -> Unit>>) {
    val ink = LocalInk.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ink.card)) {
        items.forEachIndexed { i, (title, on, set) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = InkType.body, color = ink.label, modifier = Modifier.weight(1f))
                Switch(on, set, colors = SwitchDefaults.colors(checkedTrackColor = ink.accent, checkedThumbColor = Color.White))
            }
            if (i < items.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp), color = ink.separator)
        }
    }
}
