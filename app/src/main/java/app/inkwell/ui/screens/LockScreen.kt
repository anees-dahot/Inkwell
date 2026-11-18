package app.inkwell.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.inkwell.ui.components.FilledPill
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk

/** Opaque cover shown while the app is locked; swallows all touches. */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    val ink = LocalInk.current
    Box(
        Modifier.fillMaxSize().background(ink.bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Lock, null, tint = ink.accent, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(16.dp))
            Text("Inkwell is locked", style = InkType.title, color = ink.label)
            Spacer(Modifier.height(24.dp))
            FilledPill("Unlock", onClick = onUnlock)
        }
    }
}
