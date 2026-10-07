package ru.mars.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Small process-wide thumbnail cache for the music UI. */
object ImageLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = object : LruCache<String, Bitmap>(20) {}

    fun load(rawUrl: String, imageView: ImageView) {
        val url = ThumbnailUrlResolver.resolve(rawUrl)
        if (url == null) {
            imageView.tag = null
            imageView.setImageDrawable(null)
            imageView.visibility = View.GONE
            return
        }
        imageView.visibility = View.VISIBLE
        imageView.tag = url
        cache.get(url)?.let { imageView.setImageBitmap(it); return }
        scope.launch {
            val bitmap = fetch(url)
            if (bitmap != null) cache.put(url, bitmap)
            withContext(Dispatchers.Main) {
                // Защита от stale async result: ImageView переиспользуется в списке треков,
                // применяем битмап только если tag всё ещё указывает на этот же URL.
                if (imageView.tag != url) return@withContext
                if (bitmap != null) imageView.setImageBitmap(bitmap) else imageView.visibility = View.GONE
            }
        }
    }

    private fun fetch(url: String): Bitmap? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 10000
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
