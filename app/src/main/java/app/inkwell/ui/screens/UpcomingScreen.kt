package app.inkwell.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.inkwell.data.ReleaseEstimator
import app.inkwell.data.db.CustomReminder
import app.inkwell.data.db.LibraryItem
import app.inkwell.data.model.ContentType
import app.inkwell.data.model.Entry
import app.inkwell.ui.components.*
import app.inkwell.ui.motion.pressable
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk
import app.inkwell.work.Notifier
import app.inkwell.work.Scheduler
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private val WEEKDAYS = listOf(
    Calendar.MONDAY to "Mon", Calendar.TUESDAY to "Tue", Calendar.WEDNESDAY to "Wed", Calendar.THURSDAY to "Thu",
    Calendar.FRIDAY to "Fri", Calendar.SATURDAY to "Sat", Calendar.SUNDAY to "Sun",
)
private val WEEKDAY_FULL = mapOf(
    Calendar.MONDAY to "Monday", Calendar.TUESDAY to "Tuesday", Calendar.WEDNESDAY to "Wednesday", Calendar.THURSDAY to "Thursday",
    Calendar.FRIDAY to "Friday", Calendar.SATURDAY to "Saturday", Calendar.SUNDAY to "Sunday",
)

private fun expectedLabel(i: LibraryItem): String =
    i.lastChapterNumber?.let { "Ch. ${Notifier.fmt(kotlin.math.floor(it) + 1f)} expected" } ?: "New chapter expected"

private fun dayHeader(day: Long, today: Long): String = when (day) {
    today -> "Today"
    today + 1 -> "Tomorrow"
    else -> SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date(ReleaseEstimator.startOfDay(day) + 12 * 3_600_000L))
}

@Composable
fun UpcomingScreen(onBack: () -> Unit, onOpenEntry: (Entry) -> Unit) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val ink = LocalInk.current
    val hazeState = remember { HazeState() }
    val scope = rememberCoroutineScope()
    val items by c.repo.library().collectAsStateWithLifecycle(emptyList())
    val reminders by c.repo.reminders().collectAsStateWithLifecycle(emptyList())
    val list = rememberLazyListState()
    val collapsed by list.rememberCollapsed()
    val askNotif = rememberNotificationPermission()

    val now = System.currentTimeMillis()
    val today = ReleaseEstimator.dayOf(now)
    var year by rememberSaveable { mutableIntStateOf(Calendar.getInstance().get(Calendar.YEAR)) }
    var month by rememberSaveable { mutableIntStateOf(Calendar.getInstance().get(Calendar.MONTH)) }
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var addDialog by remember { mutableStateOf(false) }

    val upcoming = remember(items) { items.filter { it.expectedNext != null }.sortedBy { it.expectedNext } }
    val byDay = remember(upcoming) { upcoming.groupBy { ReleaseEstimator.dayOf(it.expectedNext!!) } }
    val unknown = items.size - upcoming.size
    val shownDays = remember(byDay, selected) {
        (if (selected != null) byDay.filterKeys { it == selected } else byDay.filterKeys { it >= today }).toSortedMap()
    }
    val bell = remember(items) { items.filter { it.remind } }

    Box(Modifier.fillMaxSize().background(ink.bg)) {
        LazyColumn(
            state = list, modifier = Modifier.fillMaxSize().hazeSource(hazeState),
            contentPadding = PaddingValues(top = topContentPadding(), bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp),
        ) {
            item { LargeTitle("Upcoming") }
            item {
                GroupedCard {
                    MonthGrid(year, month, today, selected, byDay.keys,
                        onPrev = { if (month == 0) { month = 11; year-- } else month-- },
                        onNext = { if (month == 11) { month = 0; year++ } else month++ },
                        onSelect = { selected = if (selected == it) null else it })
                }
            }
            if (selected != null) item { SectionHeader("Releases on ${dayHeader(selected!!, today)}") }
            else item { SectionHeader("Expected releases") }
            if (shownDays.isEmpty()) item {
                GroupedCard {
                    Text(
                        if (items.isEmpty()) "Add titles to your library to see expected releases."
                        else "Nothing expected${if (selected != null) " on this day" else " yet"}. Estimates appear once a title has a few dated chapters.",
                        style = InkType.callout, color = ink.secondary, modifier = Modifier.padding(16.dp),
                    )
                }
            }
            shownDays.forEach { (day, list) ->
                item(key = "h$day") {
                    Text(
                        dayHeader(day, today), style = InkType.headline, color = ink.label,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
                    )
                }
                items(list, key = { "u${it.sourceId}/${it.entryId}" }) { i ->
                    Row(
                        Modifier.fillMaxWidth().pressable {
                            onOpenEntry(Entry(i.sourceId, i.entryId, i.title, i.coverUrl, ContentType.valueOf(i.type)))
                        }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(i.coverUrl, Modifier.width(44.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(i.title, style = InkType.headline, color = ink.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(expectedLabel(i), style = InkType.callout, color = ink.secondary, maxLines = 1)
                        }
                    }
                    Hairline(startInset = 72.dp)
                }
            }
            if (unknown > 0 && selected == null) item {
                Text(
                    "$unknown title${if (unknown == 1) "" else "s"} with unknown schedule",
                    style = InkType.caption, color = ink.secondary, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )
            }

            item { SectionHeader("Reminders") }
            item {
                GroupedCard {
                    reminders.forEach { r ->
                        ReminderRow(
                            r.title,
                            "Every ${WEEKDAY_FULL[r.weekday]} at ${"%02d:%02d".format(r.hour, r.minute)}",
                            r.enabled,
                            onToggle = { on ->
                                if (on) askNotif()
                                val n = r.copy(enabled = on)
                                scope.launch { c.repo.saveReminder(n); Scheduler.scheduleCustom(ctx, n) }
                            },
                            onDelete = { scope.launch { c.repo.deleteReminder(r.id); Scheduler.cancelCustom(ctx, r.id) } },
                        )
                        Hairline()
                    }
                    bell.forEach { i ->
                        ReminderRow(
                            i.title, "New chapters and release day", true,
                            onToggle = { on ->
                                scope.launch { c.repo.setRemind(i.sourceId, i.entryId, on); Scheduler.syncExpected(ctx) }
                            }, onDelete = null,
                        )
                        Hairline()
                    }
                    Text(
                        "Add reminder", style = InkType.body, color = ink.accent,
                        modifier = Modifier.fillMaxWidth().pressable { addDialog = true }.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
            }
            item {
                Text(
                    "Turn on the bell on a library title to get notified about new chapters and on its expected release day.",
                    style = InkType.caption, color = ink.secondary, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )
            }
        }
        GlassTopBar(
            "Upcoming", collapsed, hazeState, Modifier.align(Alignment.TopCenter),
            leading = { BarIconButton(Icons.AutoMirrored.Outlined.ArrowBackIos, "Back", onClick = onBack) },
        )
    }

    if (addDialog) AddReminderDialog(
        items = items,
        onDismiss = { addDialog = false },
        onSave = { i, weekday, h, m ->
            addDialog = false
            askNotif()
            val r = CustomReminder(UUID.randomUUID().toString(), i.sourceId, i.entryId, i.title, i.coverUrl, weekday, h, m, true)
            scope.launch { c.repo.saveReminder(r); Scheduler.scheduleCustom(ctx, r) }
        },
    )
}

@Composable
private fun ReminderRow(title: String, sub: String, on: Boolean, onToggle: (Boolean) -> Unit, onDelete: (() -> Unit)?) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = InkType.body, color = ink.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = InkType.caption, color = ink.secondary)
        }
        if (onDelete != null) {
            Box(Modifier.size(40.dp).pressable(onClick = onDelete), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Delete, "Delete", tint = ink.secondary, modifier = Modifier.size(20.dp))
            }
        }
        InkSwitch(on, onToggle)
    }
}

@Composable
fun InkSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val ink = LocalInk.current
    Switch(
        checked, onChange,
        colors = SwitchDefaults.colors(checkedTrackColor = ink.accent, checkedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent),
    )
}

@Composable
private fun MonthGrid(
    year: Int, month: Int, today: Long, selected: Long?, marked: Set<Long>,
    onPrev: () -> Unit, onNext: () -> Unit, onSelect: (Long) -> Unit,
) {
    val ink = LocalInk.current
    val first = Calendar.getInstance().apply { clear(); set(year, month, 1, 12, 0) }
    val offset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val dim = first.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDay = ReleaseEstimator.dayOf(first.timeInMillis)
    Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                SimpleDateFormat("LLLL yyyy", Locale.getDefault()).format(first.time),
                style = InkType.headline, color = ink.label, modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            BarIconButton(Icons.Outlined.ChevronLeft, "Previous month", onClick = onPrev)
            BarIconButton(Icons.Outlined.ChevronRight, "Next month", onClick = onNext)
        }
        Row(Modifier.fillMaxWidth()) {
            WEEKDAYS.forEach { (_, n) ->
                Text(n.take(1), style = InkType.caption, color = ink.secondary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        val rows = (offset + dim + 6) / 7
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth().height(44.dp)) {
                for (col in 0 until 7) {
                    val dayNum = r * 7 + col - offset + 1
                    if (dayNum < 1 || dayNum > dim) { Spacer(Modifier.weight(1f)); continue }
                    val day = firstDay + dayNum - 1
                    val isToday = day == today
                    val isSel = day == selected
                    Column(
                        Modifier.weight(1f).fillMaxHeight().pressable { onSelect(day) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(
                                when { isSel -> ink.accent; isToday -> ink.accent.copy(alpha = 0.16f); else -> Color.Transparent }
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$dayNum", fontSize = 15.sp,
                                color = if (isSel) Color.White else if (isToday) ink.accent else ink.label,
                            )
                        }
                        Box(
                            Modifier.padding(top = 2.dp).size(5.dp).clip(CircleShape)
                                .background(if (day in marked) ink.accent else Color.Transparent)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddReminderDialog(items: List<LibraryItem>, onDismiss: () -> Unit, onSave: (LibraryItem, Int, Int, Int) -> Unit) {
    val ink = LocalInk.current
    var pick by remember { mutableStateOf<LibraryItem?>(null) }
    var weekday by remember { mutableIntStateOf(Calendar.SATURDAY) }
    var hour by remember { mutableIntStateOf(10) }
    var minute by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New reminder") },
        text = {
            Column {
                if (items.isEmpty()) Text("Add a title to your library first.", color = ink.secondary)
                else {
                    Text("Title", style = InkType.caption, color = ink.secondary)
                    Column(Modifier.heightIn(max = 160.dp).verticalScrollCompat()) {
                        items.forEach { i ->
                            Text(
                                i.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (pick == i) ink.accent else ink.label,
                                modifier = Modifier.fillMaxWidth().pressable { pick = i }.padding(vertical = 8.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Repeat every", style = InkType.caption, color = ink.secondary)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WEEKDAYS.forEach { (d, n) -> Chip(n, selected = weekday == d) { weekday = d } }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("At", style = InkType.caption, color = ink.secondary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Stepper("%02d".format(hour), { hour = (hour + 23) % 24 }, { hour = (hour + 1) % 24 })
                        Text(" : ", color = ink.label)
                        Stepper("%02d".format(minute), { minute = (minute + 55) % 60 }, { minute = (minute + 5) % 60 })
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = pick != null, onClick = { onSave(pick!!, weekday, hour, minute) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.then(Modifier.verticalScroll(rememberScrollState()))

@Composable
private fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    val ink = LocalInk.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("−", color = ink.accent, fontSize = 22.sp, modifier = Modifier.pressable(onClick = onMinus).padding(horizontal = 12.dp, vertical = 4.dp))
        Text(value, color = ink.label, style = InkType.headline)
        Text("+", color = ink.accent, fontSize = 22.sp, modifier = Modifier.pressable(onClick = onPlus).padding(horizontal = 12.dp, vertical = 4.dp))
    }
}
