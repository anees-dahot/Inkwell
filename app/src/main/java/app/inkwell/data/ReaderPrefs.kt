package app.inkwell.data

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ReaderViewMode(val label: String) { LTR("Paged →"), RTL("Paged ←"), VERTICAL("Vertical"), WEBTOON("Webtoon") }
enum class ReaderScale(val label: String) { FIT_SCREEN("Fit screen"), FIT_WIDTH("Fit width"), ORIGINAL("Original") }
enum class ReaderBackground(val label: String) { BLACK("Black"), WHITE("White"), GRAY("Gray") }
enum class ReaderTapZones(val label: String) { OFF("Off"), ON("On"), INVERTED("Inverted") }

/** Manga reader preferences (global defaults + per-title mode override). */
class ReaderPrefs(private val sp: SharedPreferences) {
    private inline fun <reified E : Enum<E>> enumOf(s: String?, def: E): E =
        runCatching { enumValueOf<E>(s!!) }.getOrDefault(def)

    /** Explicit global default mode; null = derive from the legacy [Settings] mode + direction. */
    private val _viewMode = MutableStateFlow(sp.getString("rd_view", null)?.let { runCatching { ReaderViewMode.valueOf(it) }.getOrNull() })
    val viewMode: StateFlow<ReaderViewMode?> = _viewMode
    fun setViewMode(v: ReaderViewMode?) {
        _viewMode.value = v
        sp.edit().apply { if (v == null) remove("rd_view") else putString("rd_view", v.name) }.apply()
    }

    fun titleMode(sourceId: String, entryId: String): ReaderViewMode? =
        sp.getString("rd_title_${sourceId}_$entryId", null)?.let { runCatching { ReaderViewMode.valueOf(it) }.getOrNull() }

    fun setTitleMode(sourceId: String, entryId: String, v: ReaderViewMode?) {
        sp.edit().apply {
            if (v == null) remove("rd_title_${sourceId}_$entryId") else putString("rd_title_${sourceId}_$entryId", v.name)
        }.apply()
    }

    private val _scale = MutableStateFlow(enumOf(sp.getString("rd_scale", null), ReaderScale.FIT_SCREEN))
    val scale: StateFlow<ReaderScale> = _scale
    fun setScale(v: ReaderScale) { _scale.value = v; sp.edit().putString("rd_scale", v.name).apply() }

    private val _bg = MutableStateFlow(enumOf(sp.getString("rd_bg", null), ReaderBackground.BLACK))
    val background: StateFlow<ReaderBackground> = _bg
    fun setBackground(v: ReaderBackground) { _bg.value = v; sp.edit().putString("rd_bg", v.name).apply() }

    private val _tap = MutableStateFlow(enumOf(sp.getString("rd_tap", null), ReaderTapZones.ON))
    val tapZones: StateFlow<ReaderTapZones> = _tap
    fun setTapZones(v: ReaderTapZones) { _tap.value = v; sp.edit().putString("rd_tap", v.name).apply() }

    private val _keepOn = MutableStateFlow(sp.getBoolean("rd_keepon", true))
    val keepScreenOn: StateFlow<Boolean> = _keepOn
    fun setKeepScreenOn(v: Boolean) { _keepOn.value = v; sp.edit().putBoolean("rd_keepon", v).apply() }

    private val _pageNum = MutableStateFlow(sp.getBoolean("rd_pagenum", true))
    val pageNumber: StateFlow<Boolean> = _pageNum
    fun setPageNumber(v: Boolean) { _pageNum.value = v; sp.edit().putBoolean("rd_pagenum", v).apply() }

    private val _volume = MutableStateFlow(sp.getBoolean("rd_volume", false))
    val volumeKeys: StateFlow<Boolean> = _volume
    fun setVolumeKeys(v: Boolean) { _volume.value = v; sp.edit().putBoolean("rd_volume", v).apply() }

    private val _gap = MutableStateFlow(sp.getBoolean("rd_gap", false))
    val webtoonGap: StateFlow<Boolean> = _gap
    fun setWebtoonGap(v: Boolean) { _gap.value = v; sp.edit().putBoolean("rd_gap", v).apply() }

    /** Custom window brightness 0.01..1; negative = follow system. */
    private val _bright = MutableStateFlow(sp.getFloat("rd_bright", -1f))
    val brightness: StateFlow<Float> = _bright
    fun setBrightness(v: Float) { _bright.value = v; sp.edit().putFloat("rd_bright", v).apply() }
}
