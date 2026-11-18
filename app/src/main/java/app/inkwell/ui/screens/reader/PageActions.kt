package app.inkwell.ui.screens.reader

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import app.inkwell.ui.components.fetchPageFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PageActions {
    /** Saving to Pictures via MediaStore needs no permission on API 29+; hidden below. */
    val canSave: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    suspend fun saveToPictures(context: Context, url: String, name: String): Uri? = withContext(Dispatchers.IO) {
        if (!canSave) return@withContext null
        val file = fetchPageFile(context, url)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, opts)
        val mime = opts.outMimeType ?: "image/jpeg"
        val ext = mime.substringAfter('/').replace("jpeg", "jpg")
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.$ext")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Inkwell")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    /** Share the image (API 29+: via a MediaStore copy; older: the image link). */
    suspend fun share(context: Context, url: String, name: String): Intent {
        val uri = saveToPictures(context, url, name)
        val send = Intent(Intent.ACTION_SEND).apply {
            if (uri != null) {
                type = context.contentResolver.getType(uri) ?: "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
        }
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
