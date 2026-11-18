package app.inkwell.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.inkwell.ui.motion.pressable
import app.inkwell.ui.theme.LocalInk

/** Returns a function that asks for POST_NOTIFICATIONS on Android 13+ (no-op when granted / older). */
@Composable
fun rememberNotificationPermission(): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) { app.inkwell.AppLock.skipNextLock = true; launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
}

/** Bell toggle pill used on the details page. */
@Composable
fun ReminderBell(on: Boolean, onClick: () -> Unit) {
    val ink = LocalInk.current
    Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(10.dp))
            .background(if (on) ink.accent else ink.accent.copy(alpha = 0.14f)).pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (on) Icons.Filled.Notifications else Icons.Outlined.NotificationsNone,
            if (on) "Reminders on" else "Reminders off",
            tint = if (on) Color.White else ink.accent, modifier = Modifier.size(22.dp),
        )
    }
}
