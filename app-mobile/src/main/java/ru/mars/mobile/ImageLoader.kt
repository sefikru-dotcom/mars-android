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

    fun load(url: String, imageView: ImageView) {
        if (url.isBlank()) { imageView.visibility = View.GONE; return }
        imageView.visibility = View.VISIBLE
        cache.get(url)?.let { imageView.setImageBitmap(it); return }
        scope.launch {
            val bitmap = runCatching {
                (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = 8000; readTimeout = 10000
                    inputStream.use { BitmapFactory.decodeStream(it) }.also { disconnect() }
                }
            }.getOrNull()
            if (bitmap != null) cache.put(url, bitmap)
            withContext(Dispatchers.Main) {
                if (bitmap != null) imageView.setImageBitmap(bitmap) else imageView.visibility = View.GONE
            }
        }
    }
}
