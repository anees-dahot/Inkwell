package app.inkwell.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.inkwell.data.db.LibraryCategory
import app.inkwell.ui.motion.pressable
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk

/** Pick one category (or none = 0). [current] is highlighted (-1 = nothing highlighted). */
@Composable
fun CategoryPickerDialog(
    title: String,
    categories: List<LibraryCategory>,
    current: Long = -1,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val ink = LocalInk.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                items(listOf(LibraryCategory(0, "No category", 0)) + categories, key = { it.id }) { c ->
                    Row(
                        Modifier.fillMaxWidth().pressable { onPick(c.id) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(c.name, style = InkType.body, color = if (c.id == current) ink.accent else ink.label, modifier = Modifier.weight(1f))
                        if (c.id == current) Icon(Icons.Outlined.Check, null, tint = ink.accent, modifier = Modifier.size(18.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
