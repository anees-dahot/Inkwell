package app.inkwell.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.inkwell.data.source.ScraperSource
import app.inkwell.ui.components.BarIconButton
import app.inkwell.ui.components.TopBarHeight
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk

/** In-app browser used to pass Cloudflare checks; cookies are shared with the OkHttp client. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(url: String, onBack: () -> Unit) {
    val ink = LocalInk.current
    var title by remember { mutableStateOf(url) }
    var progress by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun close() { runCatching { CookieManager.getInstance().flush() }; onBack() }
    BackHandler {
        val w = webView
        if (w != null && w.canGoBack()) w.goBack() else close()
    }
    DisposableEffect(Unit) { onDispose { webView?.destroy(); runCatching { CookieManager.getInstance().flush() } } }

    Column(Modifier.fillMaxSize().background(ink.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(TopBarHeight).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BarIconButton(Icons.AutoMirrored.Outlined.ArrowBackIos, "Close", onClick = ::close)
            Text(title, style = InkType.headline, color = ink.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            BarIconButton(Icons.Outlined.Refresh, "Reload") { webView?.reload() }
        }
        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = ink.accent)
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = ScraperSource.globalUserAgent
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, u: String?) {
                            title = view.title?.takeIf { it.isNotBlank() } ?: u ?: url
                            CookieManager.getInstance().flush()
                        }
                    }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onProgressChanged(view: WebView, p: Int) { progress = p }
                    }
                    webView = this
                    loadUrl(url)
                }
            },
        )
    }
}
