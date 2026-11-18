package app.inkwell

import android.graphics.Color
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.inkwell.data.ThemeMode
import app.inkwell.ui.AppNav
import app.inkwell.ui.components.LocalContainer
import app.inkwell.ui.motion.LocalReducedMotion
import app.inkwell.ui.theme.InkwellTheme

class MainActivity : ComponentActivity() {
    private var reducedMotion by mutableStateOf(false)

    private fun readReduced() {
        reducedMotion = AndroidSettings.Global.getFloat(contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as InkwellApp).container
        readReduced()
        setContent {
            val theme by container.settings.theme.collectAsStateWithLifecycle()
            val dark = when (theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
                onDispose { }
            }
            CompositionLocalProvider(
                LocalContainer provides container,
                LocalReducedMotion provides reducedMotion,
            ) {
                InkwellTheme(dark) { AppNav() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        readReduced()
    }
}
