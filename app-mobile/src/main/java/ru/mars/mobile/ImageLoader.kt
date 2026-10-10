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
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.lang.ref.WeakReference

/**
 * Small process-wide thumbnail cache for the music UI.
 *
 * Memory-safety rules (policies live in [ImageLoaderPolicy], JVM-tested):
 * - the LruCache is BYTE-based (bitmap.byteCount) with a budget of 1/8 of the heap clamped
 *   to 2..24 MB, so 20 large covers can no longer blow the cache accounting;
 * - every decode is downsampled to the target view size (power-of-two inSampleSize);
 * - oversized downloads are rejected by Content-Length and by a streaming byte cap;
 * - the fetch coroutine holds only a [WeakReference] to the ImageView: a slow request no
 *   longer pins the view (and through it the Activity) after the screen is closed.
 *
 * Known limitation: there is no disk cache — adding one means owning eviction, journaling
 * and cache-dir lifecycle, a rewrite out of scope here; refetch happens after process death
 * or LRU eviction. Cached bitmaps are keyed by URL only: the first requested target size
 * wins for subsequent differently-sized views of the same URL.
 */
object ImageLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = object : LruCache<String, Bitmap>(
        ImageLoaderPolicy.cacheBudgetBytes(Runtime.getRuntime().maxMemory())
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

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
        // Decode target must be read on the main thread before the view is recycled;
        // 0x0 (not laid out yet) falls back to ImageLoaderPolicy.DEFAULT_TARGET_PX.
        val targetPx = ImageLoaderPolicy.targetSizePx(imageView.width, imageView.height)
        val viewRef = WeakReference(imageView)
        scope.launch {
            val bitmap = fetch(url, targetPx)
            if (bitmap != null) cache.put(url, bitmap)
            withContext(Dispatchers.Main) {
                // Защита от stale async result и утечки: ImageView держится только через
                // WeakReference; если view собрана сборщиком или переиспользована (tag
                // указывает на другой URL), результат просто выбрасывается.
                val view = viewRef.get() ?: return@withContext
                if (view.tag != url) return@withContext
                if (bitmap != null) view.setImageBitmap(bitmap) else view.visibility = View.GONE
            }
        }
    }

    private fun fetch(url: String, targetPx: Int): Bitmap? = runCatching {
        val bytes = download(url) ?: return@runCatching null
        // Two-pass decode over the in-memory payload: bounds first, then downsampled decode.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageLoaderPolicy.sampleSize(bounds.outWidth, bounds.outHeight, targetPx)
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()

    /**
     * Streams the response into memory with a hard [ImageLoaderPolicy.MAX_DOWNLOAD_BYTES]
     * cap; returns null when the server advertises or actually sends more than the cap.
     */
    private fun download(url: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 10000
            if (!ImageLoaderPolicy.acceptDownload(connection.contentLengthLong)) return null
            val cap = ImageLoaderPolicy.MAX_DOWNLOAD_BYTES
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > cap) return null
                    out.write(buffer, 0, read)
                }
            }
            return out.toByteArray()
        } finally {
            connection.disconnect()
        }
    }
}
