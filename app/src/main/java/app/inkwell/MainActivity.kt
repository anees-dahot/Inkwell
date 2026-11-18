package app.inkwell

import android.graphics.Color
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import app.inkwell.ui.screens.LockScreen
import app.inkwell.ui.screens.OnboardingScreen
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

class MainActivity : FragmentActivity() {
    private var reducedMotion by mutableStateOf(false)
    private var locked by mutableStateOf(false)
    private var prompting = false
    private var stoppedAt = 0L

    private val settings get() = (application as InkwellApp).container.settings

    private fun lockWanted() = settings.lockMinutes.value >= 0 && AppLock.available(this)

    private fun promptUnlock() {
        if (!locked || prompting) return
        prompting = true
        AppLock.prompt(this, "Unlock Inkwell", onSuccess = { locked = false }, onDone = { prompting = false })
    }

    private fun readReduced() {
        reducedMotion = AndroidSettings.Global.getFloat(contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as InkwellApp).container
        readReduced()
        AppLaunch.handle(intent)
        locked = lockWanted()
        container.updater.autoCheckOnStart()
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
                val accent by container.settings.accent.collectAsStateWithLifecycle()
                val amoled by container.settings.amoled.collectAsStateWithLifecycle()
                val dynamic by container.settings.dynamicColor.collectAsStateWithLifecycle()
                val secure by container.settings.secureScreen.collectAsStateWithLifecycle()
                val incognito by container.settings.incognito.collectAsStateWithLifecycle()
                val onboarded by container.settings.onboarded.collectAsStateWithLifecycle()
                DisposableEffect(secure) {
                    if (secure) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    onDispose { }
                }
                InkwellTheme(dark, androidx.compose.ui.graphics.Color(accent.argb), amoled, dynamic) {
                    Box(Modifier.fillMaxSize()) {
                        if (onboarded) AppNav() else OnboardingScreen(onDone = { })
                        if (incognito) IncognitoPill(Modifier.align(Alignment.TopCenter))
                        if (onboarded && !locked) app.inkwell.ui.components.UpdateDialog(container.updater)
                        if (locked) LockScreen(onUnlock = { promptUnlock() })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AppLaunch.handle(intent)
    }

    override fun onStart() {
        super.onStart()
        val min = settings.lockMinutes.value
        if (AppLock.skipNextLock) { AppLock.skipNextLock = false; stoppedAt = 0 }
        if (stoppedAt > 0 && min >= 0 && lockWanted() && SystemClock.elapsedRealtime() - stoppedAt >= min * 60_000L) locked = true
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun onResume() {
        super.onResume()
        readReduced()
        promptUnlock()
    }
}

/** Small pill under the status bar while incognito mode is on. */
@Composable
private fun IncognitoPill(modifier: Modifier = Modifier) {
    val top = (WindowInsets.statusBars.asPaddingValues().calculateTopPadding() - 16.dp).coerceAtLeast(0.dp)
    Row(
        modifier.padding(top = top).clip(RoundedCornerShape(50)).background(androidx.compose.ui.graphics.Color(0xE62C2C2E))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.VisibilityOff, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(4.dp))
        Text("Incognito", color = androidx.compose.ui.graphics.Color.White, fontSize = 10.sp)
    }
}
