package app.inkwell.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.inkwell.AppLaunch
import app.inkwell.InkwellApp
import app.inkwell.MainActivity
import app.inkwell.data.db.HistoryItem
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object InkwellWidgets {
    suspend fun refresh(context: Context) {
        ContinueReadingWidget().updateAll(context)
        UpcomingWidget().updateAll(context)
    }
}

private val Bg = ColorProvider(Color.White, Color(0xFF1C1C1E))
private val Label = ColorProvider(Color.Black, Color.White)
private val Secondary = ColorProvider(Color(0x99000000), Color(0x99FFFFFF))
private val Placeholder = ColorProvider(Color(0x1F000000), Color(0x2EFFFFFF))

private fun container(context: Context) = (context.applicationContext as InkwellApp).container

private fun accent(context: Context): androidx.glance.unit.ColorProvider {
    val c = Color(container(context).settings.accent.value.argb)
    return ColorProvider(c, c)
}

private fun launch(context: Context, s: String, e: String, title: String, cover: String?, ep: String?): androidx.glance.action.Action {
    val i = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse("inkwell://open/${Uri.encode(s)}/${Uri.encode(e)}/${Uri.encode(ep ?: "-")}")
        putExtra(AppLaunch.EXTRA_SOURCE, s); putExtra(AppLaunch.EXTRA_ENTRY, e)
        putExtra(AppLaunch.EXTRA_TITLE, title); putExtra(AppLaunch.EXTRA_COVER, cover)
        if (ep != null) putExtra(AppLaunch.EXTRA_EPISODE, ep)
        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    return actionStartActivity(i)
}

private fun openApp(context: Context) = actionStartActivity(
    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
)

private suspend fun loadCovers(context: Context, urls: List<String?>): List<Bitmap?> = coroutineScope {
    val loader = SingletonImageLoader.get(context)
    urls.map { u ->
        async(Dispatchers.IO) {
            if (u == null) null else withTimeoutOrNull(6000) {
                val r = loader.execute(ImageRequest.Builder(context).data(u).size(Size(150, 210)).allowHardware(false).build())
                (r as? SuccessResult)?.image?.toBitmap()
            }
        }
    }.awaitAll()
}

class ContinueReadingWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val items: List<HistoryItem> = withContext(Dispatchers.IO) { container(context).db.history().recent(4) }
        val covers = loadCovers(context, items.map { it.coverUrl })
        val accent = accent(context)
        provideContent {
            Column(GlanceModifier.fillMaxSize().background(Bg).cornerRadius(20.dp).padding(12.dp)) {
                Text("Continue reading", style = TextStyle(color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold), modifier = GlanceModifier.clickable(openApp(context)))
                Spacer(GlanceModifier.height(6.dp))
                if (items.isEmpty()) Text("Nothing yet. Open a chapter and it will show up here.", style = TextStyle(color = Secondary, fontSize = 13.sp))
                items.forEachIndexed { i, h ->
                    Row(
                        GlanceModifier.fillMaxWidth().padding(vertical = 3.dp).clickable(launch(context, h.sourceId, h.entryId, h.entryTitle, h.coverUrl, h.episodeId)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val bmp = covers.getOrNull(i)
                        if (bmp != null) Image(ImageProvider(bmp), null, GlanceModifier.size(width = 28.dp, height = 40.dp).cornerRadius(5.dp), contentScale = ContentScale.Crop)
                        else Box(GlanceModifier.size(width = 28.dp, height = 40.dp).background(Placeholder).cornerRadius(5.dp)) {}
                        Spacer(GlanceModifier.width(10.dp))
                        Column {
                            Text(h.entryTitle, maxLines = 1, style = TextStyle(color = Label, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                            Text(h.episodeName, maxLines = 1, style = TextStyle(color = Secondary, fontSize = 12.sp))
                        }
                    }
                }
            }
        }
    }
}

class UpcomingWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val startOfToday = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val items = withContext(Dispatchers.IO) {
            container(context).db.library().all().filter { (it.expectedNext ?: 0L) >= startOfToday }.sortedBy { it.expectedNext }.take(4)
        }
        val fmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
        val accent = accent(context)
        provideContent {
            Column(GlanceModifier.fillMaxSize().background(Bg).cornerRadius(20.dp).padding(12.dp)) {
                Text("Upcoming releases", style = TextStyle(color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold), modifier = GlanceModifier.clickable(openApp(context)))
                Spacer(GlanceModifier.height(6.dp))
                if (items.isEmpty()) Text("No releases expected yet.", style = TextStyle(color = Secondary, fontSize = 13.sp))
                items.forEach { it ->
                    Row(
                        GlanceModifier.fillMaxWidth().padding(vertical = 4.dp).clickable(launch(context, it.sourceId, it.entryId, it.title, it.coverUrl, null)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(it.title, maxLines = 1, style = TextStyle(color = Label, fontSize = 14.sp, fontWeight = FontWeight.Medium), modifier = GlanceModifier.defaultWeight())
                        Spacer(GlanceModifier.width(8.dp))
                        Text(fmt.format(Date(it.expectedNext!!)), style = TextStyle(color = Secondary, fontSize = 12.sp))
                    }
                }
            }
        }
    }
}

class ContinueReadingReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ContinueReadingWidget()
}

class UpcomingReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UpcomingWidget()
}
