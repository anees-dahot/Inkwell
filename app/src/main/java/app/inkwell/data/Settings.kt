package app.inkwell.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class AccentColor(val label: String, val argb: Long) {
    PINK("Pink", 0xFFFF2D55), ORANGE("Orange", 0xFFFF9500), GREEN("Green", 0xFF34C759), TEAL("Teal", 0xFF30B0C7),
    BLUE("Blue", 0xFF0A84FF), INDIGO("Indigo", 0xFF5E5CE6), PURPLE("Purple", 0xFFAF52DE), RED("Red", 0xFFE5342B),
}
enum class ReaderMode { AUTO, WEBTOON, PAGED }
enum class ReaderDirection { LTR, RTL }

enum class NovelTheme(val label: String) { FOLLOW("App"), LIGHT("Light"), SEPIA("Sepia"), DARK("Dark"), BLACK("Black") }
enum class NovelFont(val label: String, val sys: String) {
    SANS("Sans", "sans-serif"), SERIF("Serif", "serif"), MONO("Mono", "monospace"),
    CONDENSED("Condensed", "sans-serif-condensed"), LIGHT("Light", "sans-serif-light"),
    MEDIUM("Medium", "sans-serif-medium"), CASUAL("Casual", "casual"), CURSIVE("Cursive", "cursive"),
}

/** Novel reader preferences (LNReader / Moon+ style). */
data class NovelPrefs(
    val fontSize: Float = 18f,
    val lineSpacing: Float = 1.6f,
    val paraSpacing: Float = 14f,
    val margin: Float = 20f,
    val justify: Boolean = false,
    val font: NovelFont = NovelFont.SANS,
    val theme: NovelTheme = NovelTheme.FOLLOW,
    val keepScreenOn: Boolean = true,
    val volumeKeys: Boolean = false,
    val tapZones: Boolean = false,
    val continuous: Boolean = false,
    val ttsRate: Float = 1f,
    val ttsPitch: Float = 1f,
)

class Settings(context: Context) {
    private val sp = context.getSharedPreferences("inkwell", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(enumOf(sp.getString("theme", null), ThemeMode.SYSTEM))
    val theme: StateFlow<ThemeMode> = _theme
    fun setTheme(v: ThemeMode) { _theme.value = v; sp.edit().putString("theme", v.name).apply() }

    private val _lang = MutableStateFlow(sp.getString("lang", "en") ?: "en")
    val lang: StateFlow<String> = _lang
    fun setLang(v: String) { _lang.value = v; sp.edit().putString("lang", v).apply() }

    private val _mode = MutableStateFlow(run {
        // v1.5: long strip is the default reading mode; move users still on the old default over once
        if (!sp.getBoolean("mode_v15", false)) {
            sp.edit().putBoolean("mode_v15", true).apply {
                if (sp.getString("mode", null).let { it == null || it == ReaderMode.AUTO.name }) putString("mode", ReaderMode.WEBTOON.name)
            }.apply()
        }
        enumOf(sp.getString("mode", null), ReaderMode.WEBTOON)
    })
    val readerMode: StateFlow<ReaderMode> = _mode
    fun setReaderMode(v: ReaderMode) { _mode.value = v; sp.edit().putString("mode", v.name).apply() }

    private val _dir = MutableStateFlow(enumOf(sp.getString("dir", null), ReaderDirection.LTR))
    val readerDirection: StateFlow<ReaderDirection> = _dir
    fun setReaderDirection(v: ReaderDirection) { _dir.value = v; sp.edit().putString("dir", v.name).apply() }

    val reader = ReaderPrefs(sp)

    private val _dlWifi = MutableStateFlow(sp.getBoolean("dl_wifi", true))
    val downloadWifiOnly: StateFlow<Boolean> = _dlWifi
    fun setDownloadWifiOnly(v: Boolean) { _dlWifi.value = v; sp.edit().putBoolean("dl_wifi", v).apply() }

    private val _dlAuto = MutableStateFlow(sp.getBoolean("dl_auto", false))
    val autoDownload: StateFlow<Boolean> = _dlAuto
    fun setAutoDownload(v: Boolean) { _dlAuto.value = v; sp.edit().putBoolean("dl_auto", v).apply() }

    /** Last selected source per content type (key = ContentType.name). */
    fun lastSource(type: String): String? = sp.getString("last_src_$type", null)

    /** Most-recently-used source ids for a type, newest first (max 5). */
    fun recentSources(type: String): List<String> =
        sp.getString("recent_src_$type", null)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    fun rememberSource(type: String, id: String) {
        val list = (listOf(id) + recentSources(type).filter { it != id }).take(5)
        sp.edit().putString("last_src_$type", id).putString("recent_src_$type", list.joinToString(",")).apply()
    }

    /** Library auto-update interval in hours; 0 = off. */
    private val _updateHours = MutableStateFlow(sp.getInt("upd_hours", 0))
    val updateHours: StateFlow<Int> = _updateHours
    fun setUpdateHours(v: Int) { _updateHours.value = v; sp.edit().putInt("upd_hours", v).apply() }

    private val _wifiOnly = MutableStateFlow(sp.getBoolean("upd_wifi", true))
    val updateWifiOnly: StateFlow<Boolean> = _wifiOnly
    fun setUpdateWifiOnly(v: Boolean) { _wifiOnly.value = v; sp.edit().putBoolean("upd_wifi", v).apply() }

    private val _charging = MutableStateFlow(sp.getBoolean("upd_charging", false))
    val updateCharging: StateFlow<Boolean> = _charging
    fun setUpdateCharging(v: Boolean) { _charging.value = v; sp.edit().putBoolean("upd_charging", v).apply() }

    private fun loadNovel() = NovelPrefs(
        fontSize = sp.getFloat("nv_size", 18f), lineSpacing = sp.getFloat("nv_line", 1.6f),
        paraSpacing = sp.getFloat("nv_para", 14f), margin = sp.getFloat("nv_margin", 20f),
        justify = sp.getBoolean("nv_justify", false), font = enumOf(sp.getString("nv_font", null), NovelFont.SANS),
        theme = enumOf(sp.getString("nv_theme", null), NovelTheme.FOLLOW),
        keepScreenOn = sp.getBoolean("nv_keepon", true), volumeKeys = sp.getBoolean("nv_vol", false),
        tapZones = sp.getBoolean("nv_tap", false), continuous = sp.getBoolean("nv_cont", false),
        ttsRate = sp.getFloat("nv_rate", 1f), ttsPitch = sp.getFloat("nv_pitch", 1f),
    )
    private val _novel = MutableStateFlow(loadNovel())
    val novel: StateFlow<NovelPrefs> = _novel
    fun updateNovel(f: (NovelPrefs) -> NovelPrefs) {
        val v = f(_novel.value); _novel.value = v
        sp.edit().putFloat("nv_size", v.fontSize).putFloat("nv_line", v.lineSpacing).putFloat("nv_para", v.paraSpacing)
            .putFloat("nv_margin", v.margin).putBoolean("nv_justify", v.justify).putString("nv_font", v.font.name)
            .putString("nv_theme", v.theme.name).putBoolean("nv_keepon", v.keepScreenOn).putBoolean("nv_vol", v.volumeKeys)
            .putBoolean("nv_tap", v.tapZones).putBoolean("nv_cont", v.continuous)
            .putFloat("nv_rate", v.ttsRate).putFloat("nv_pitch", v.ttsPitch).apply()
    }

    // appearance
    private val _accent = MutableStateFlow(enumOf(sp.getString("accent", null), AccentColor.PINK))
    val accent: StateFlow<AccentColor> = _accent
    fun setAccent(v: AccentColor) { _accent.value = v; sp.edit().putString("accent", v.name).apply() }

    private val _amoled = MutableStateFlow(sp.getBoolean("amoled", false))
    val amoled: StateFlow<Boolean> = _amoled
    fun setAmoled(v: Boolean) { _amoled.value = v; sp.edit().putBoolean("amoled", v).apply() }

    private val _dynamic = MutableStateFlow(sp.getBoolean("dynamic_color", false))
    val dynamicColor: StateFlow<Boolean> = _dynamic
    fun setDynamicColor(v: Boolean) { _dynamic.value = v; sp.edit().putBoolean("dynamic_color", v).apply() }

    // privacy
    /** Minutes in background before the app locks; -1 = lock disabled, 0 = immediately. */
    private val _lockMin = MutableStateFlow(sp.getInt("lock_min", -1))
    val lockMinutes: StateFlow<Int> = _lockMin
    fun setLockMinutes(v: Int) { _lockMin.value = v; sp.edit().putInt("lock_min", v).apply() }

    private val _secure = MutableStateFlow(sp.getBoolean("secure_screen", false))
    val secureScreen: StateFlow<Boolean> = _secure
    fun setSecureScreen(v: Boolean) { _secure.value = v; sp.edit().putBoolean("secure_screen", v).apply() }

    private val _incognito = MutableStateFlow(sp.getBoolean("incognito", false))
    val incognito: StateFlow<Boolean> = _incognito

    private val _showAdult = MutableStateFlow(sp.getBoolean("show_adult", true))
    /** Show 18+ sources and titles in Browse, tags and source comparisons. */
    val showAdult: StateFlow<Boolean> = _showAdult
    fun setShowAdult(v: Boolean) { _showAdult.value = v; sp.edit().putBoolean("show_adult", v).apply() }
    fun setIncognito(v: Boolean) { _incognito.value = v; sp.edit().putBoolean("incognito", v).apply() }

    // onboarding: existing installs (prefs or DB already present) skip it
    private val _onboarded = MutableStateFlow(
        if (sp.contains("onboarded")) sp.getBoolean("onboarded", true)
        else (sp.all.isNotEmpty() || context.getDatabasePath("inkwell.db").exists()).also { sp.edit().putBoolean("onboarded", it).apply() }
    )
    val onboarded: StateFlow<Boolean> = _onboarded
    fun setOnboarded(v: Boolean) { _onboarded.value = v; sp.edit().putBoolean("onboarded", v).apply() }

    /** ContentType name Browse opens on (set by onboarding). */
    var defaultBrowseType: String?
        get() = sp.getString("browse_type", null)
        set(v) { sp.edit().putString("browse_type", v).apply() }

    private inline fun <reified E : Enum<E>> enumOf(s: String?, def: E): E =
        runCatching { enumValueOf<E>(s!!) }.getOrDefault(def)
}
