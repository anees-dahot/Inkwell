package app.inkwell.ui.components

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.inkwell.ui.screens.topContentPadding
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Emits whether the device currently has an internet-capable network. */
fun connectivityFlow(ctx: Context): Flow<Boolean> = callbackFlow {
    val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    fun now(): Boolean = runCatching {
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)
    val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { trySend(true) }
        override fun onLost(network: Network) { trySend(now()) }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        }
    }
    trySend(now())
    val reg = runCatching { cm.registerDefaultNetworkCallback(cb) }.isSuccess
    awaitClose { if (reg) runCatching { cm.unregisterNetworkCallback(cb) } }
}.distinctUntilChanged()

@Composable
fun rememberIsOnline(): State<Boolean> {
    val ctx = LocalContext.current
    return remember { connectivityFlow(ctx) }.collectAsState(initial = true)
}

/** Small pill shown while offline; place it at the top of a screen's Box (it sits under the top bar). */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    val online by rememberIsOnline()
    val ink = LocalInk.current
    AnimatedVisibility(
        visible = !online, modifier = modifier,
        enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it },
    ) {
        Row(
            Modifier.padding(top = topContentPadding()).clip(RoundedCornerShape(50)).background(ink.card)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.WifiOff, null, tint = ink.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("You're offline. Showing saved data.", style = InkType.caption, color = ink.label)
        }
    }
}
