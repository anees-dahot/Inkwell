package app.inkwell.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.inkwell.data.AppUpdater
import app.inkwell.data.UpdateInfo
import app.inkwell.data.UpdateState
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk

/** Strip common markdown so release notes read as plain text. */
fun markdownToPlain(md: String): String = md.lines().joinToString("\n") { l ->
    l.trim().replace(Regex("^#{1,6}\\s*"), "")
        .replace(Regex("^[-*+]\\s+"), "• ")
        .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace("**", "").replace("__", "").replace("`", "")
}.replace(Regex("\n{3,}"), "\n\n").trim()

@Composable
fun UpdateDialog(updater: AppUpdater) {
    val state by updater.state.collectAsStateWithLifecycle()
    val dismissed by updater.dialogDismissed.collectAsStateWithLifecycle()
    if (dismissed) return
    val ink = LocalInk.current
    val info: UpdateInfo
    var progress: Float? = null
    var ready = false
    when (val s = state) {
        is UpdateState.Available -> info = s.info
        is UpdateState.Downloading -> { info = s.info; progress = s.progress }
        is UpdateState.ReadyToInstall -> { info = s.info; ready = true }
        else -> return
    }
    val downloading = progress != null
    AlertDialog(
        onDismissRequest = { if (!downloading) updater.dismissDialog() },
        title = { Text("Update available v${info.version}") },
        text = {
            Column {
                val size = AppUpdater.formatSize(info.apkSize)
                if (size.isNotEmpty()) Text("Size: $size", style = InkType.caption, color = ink.secondary)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        markdownToPlain(info.notes).ifEmpty { "No release notes." },
                        style = InkType.callout, color = ink.label,
                    )
                }
                if (progress != null) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(progress * 100).toInt()}%", style = InkType.caption, color = ink.secondary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { updater.downloadAndInstall(info) }, enabled = !downloading) {
                Text(if (ready) "Install" else "Update")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { updater.skipVersion(info.version) }, enabled = !downloading) { Text("Skip") }
                TextButton(onClick = { updater.dismissDialog() }, enabled = !downloading) { Text("Later") }
            }
        },
    )
}
