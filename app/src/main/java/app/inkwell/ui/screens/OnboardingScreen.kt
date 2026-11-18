package app.inkwell.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.inkwell.data.model.ContentType
import app.inkwell.ui.components.*
import app.inkwell.ui.motion.pressable
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk
import kotlinx.coroutines.launch

@Composable
private fun Page(icon: ImageVector, title: String, text: String, content: @Composable ColumnScope.() -> Unit = {}) {
    val ink = LocalInk.current
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(ink.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = ink.accent, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(28.dp))
        Text(title, style = InkType.largeTitle, color = ink.label, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(text, style = InkType.body, color = ink.secondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        content()
    }
}

/** First-run flow: welcome, content types, updates. Skippable; shown once. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val c = LocalContainer.current
    val s = c.settings
    val ctx = LocalContext.current
    val ink = LocalInk.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { 3 }
    val askNotif = rememberNotificationPermission()
    val types = listOf(ContentType.MANGA to "Manga", ContentType.NOVEL to "Novels", ContentType.VIDEO to "Donghua")
    var picked by remember { mutableStateOf(setOf(ContentType.MANGA, ContentType.NOVEL, ContentType.VIDEO)) }
    var updates by remember { mutableStateOf(true) }
    var notifAsked by remember { mutableStateOf(false) }

    fun finish(apply: Boolean) {
        if (apply) {
            types.firstOrNull { it.first in picked }?.let { s.defaultBrowseType = it.first.name }
            if (updates) {
                s.setUpdateHours(12)
                app.inkwell.work.Scheduler.syncPeriodic(ctx, s)
            }
        }
        s.setOnboarded(true)
        onDone()
    }
    // ask for the notification permission as soon as updates are enabled on the last page
    LaunchedEffect(pager.currentPage, updates) {
        if (pager.currentPage == 2 && updates && !notifAsked) { notifAsked = true; askNotif() }
    }

    Box(Modifier.fillMaxSize().background(ink.bg).windowInsetsPadding(WindowInsets.systemBars)) {
        HorizontalPager(pager, Modifier.fillMaxSize().padding(bottom = 120.dp, top = 40.dp)) { p ->
            when (p) {
                0 -> Page(Icons.Outlined.AutoStories, "Welcome to Inkwell", "Manga, novels and donghua in one calm place. Track what you read and never miss a chapter.")
                1 -> Page(Icons.Outlined.Category, "What do you read?", "Pick what you care about. Browse will open on your first choice.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        types.forEach { (t, label) ->
                            Chip(label, selected = t in picked, onClick = { picked = if (t in picked) picked - t else picked + t })
                        }
                    }
                }
                else -> Page(Icons.Outlined.NotificationsActive, "Stay up to date", "Inkwell can check your library every 12 hours and notify you about new chapters.") {
                    GroupedCard {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Updates & notifications", style = InkType.body, color = ink.label, modifier = Modifier.weight(1f))
                            InkSwitch(updates) { updates = it }
                        }
                    }
                }
            }
        }
        Text(
            "Skip", style = InkType.body, color = ink.accent,
            modifier = Modifier.align(Alignment.TopEnd).pressable { finish(false) }.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { i ->
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (i == pager.currentPage) ink.accent else ink.secondary.copy(alpha = 0.3f)))
                }
            }
            Spacer(Modifier.height(20.dp))
            val last = pager.currentPage == 2
            FilledPill(if (last) "Get started" else "Continue", Modifier.width(220.dp)) {
                if (last) finish(true) else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            }
        }
    }
}
