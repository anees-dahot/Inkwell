package app.inkwell.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ReaderMode { AUTO, WEBTOON, PAGED }
enum class ReaderDirection { LTR, RTL }

class Settings(context: Context) {
    private val sp = context.getSharedPreferences("inkwell", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(enumOf(sp.getString("theme", null), ThemeMode.SYSTEM))
    val theme: StateFlow<ThemeMode> = _theme
    fun setTheme(v: ThemeMode) { _theme.value = v; sp.edit().putString("theme", v.name).apply() }

    private val _lang = MutableStateFlow(sp.getString("lang", "en") ?: "en")
    val lang: StateFlow<String> = _lang
    fun setLang(v: String) { _lang.value = v; sp.edit().putString("lang", v).apply() }

    private val _mode = MutableStateFlow(enumOf(sp.getString("mode", null), ReaderMode.AUTO))
    val readerMode: StateFlow<ReaderMode> = _mode
    fun setReaderMode(v: ReaderMode) { _mode.value = v; sp.edit().putString("mode", v.name).apply() }

    private val _dir = MutableStateFlow(enumOf(sp.getString("dir", null), ReaderDirection.LTR))
    val readerDirection: StateFlow<ReaderDirection> = _dir
    fun setReaderDirection(v: ReaderDirection) { _dir.value = v; sp.edit().putString("dir", v.name).apply() }

    private inline fun <reified E : Enum<E>> enumOf(s: String?, def: E): E =
        runCatching { enumValueOf<E>(s!!) }.getOrDefault(def)
}
