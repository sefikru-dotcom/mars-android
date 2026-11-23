package ru.mars.mobile.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor
import ru.mars.mobile.ImageDiskCache
import ru.mars.mobile.ImageLoaderPolicy
import ru.mars.mobile.MusicNotificationPolicy
import ru.mars.mobile.ThumbnailUrlResolver

/**
 * Cover art for the playback notification, kept off the main thread.
 *
 * Why not [ru.mars.mobile.ImageLoader]: that loader targets an `ImageView` (it holds a weak
 * reference and writes straight into the view). The shade needs a bare [Bitmap] for
 * `setLargeIcon` and for the session metadata, so this is the same three-level cache minus
 * the view plumbing: a tiny [LruCache] keyed by the policy's artwork key, the shared disk
 * cache ([ImageDiskCache]) so a track replayed later is not downloaded again, and the
 * network only when the disk has no fresh copy.
 *
 * Every decision that is really arithmetic (when to reload at all, how large to decode)
 * lives in [MusicNotificationPolicy] / [ImageLoaderPolicy] and is JVM-tested; this file is
 * the Android-bound fetch. Delivery always happens on the main thread — callers rebuild the
 * notification and the media session from [onReady].
 */
internal object MusicArtwork {

    /**
     * How many covers stay in memory. The shade shows one at a time, so this only has to
     * absorb short back-and-forth between neighbours in the queue.
     */
    private const val MAX_ENTRIES = 6

    private val cache = object : LruCache<String, Bitmap>(MAX_ENTRIES) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    private val main = Handler(Looper.getMainLooper())

    /** Already-decoded cover for [key], or null while it is still in flight. */
    fun cached(key: String): Bitmap? = cache.get(key)

    /**
     * Ensures [key] ends up in [cached]. [onReady] always runs on the main thread and is
     * called even when the fetch failed, so the caller can stop waiting; the key is passed
     * back so a result for a track that is already gone can be discarded.
     */
    fun load(key: String, executor: Executor, onReady: (String) -> Unit) {
        if (cache.get(key) != null) {
            onReady(key)
            return
        }
        executor.execute {
            val bitmap = runCatching { fetchAndDecode(key) }.getOrNull()
            if (bitmap != null) cache.put(key, bitmap)
            main.post { onReady(key) }
        }
    }

    /** Disk first, then network. Both are capped — a wrong URL must never reach the heap. */
    private fun fetchAndDecode(key: String): Bitmap? {
        val url = ThumbnailUrlResolver.resolve(key) ?: return null
        ImageDiskCache.read(url)?.let { bytes ->
            decode(bytes)?.let { return it }
            // Недекодируемые байты хранить бессмысленно: они сломают показ до конца TTL.
            ImageDiskCache.remove(url)
        }
        val downloaded = download(url) ?: return null
        val bitmap = decode(downloaded.bytes) ?: return null
        ImageDiskCache.write(url, downloaded.contentType, downloaded.bytes)
        return bitmap
    }

    /** Two-pass decode downsampled to the shade's large-icon size — never the full cover. */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageLoaderPolicy.sampleSize(
                bounds.outWidth, bounds.outHeight, MusicNotificationPolicy.ARTWORK_MAX_PX
            )
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private class Downloaded(val bytes: ByteArray, val contentType: String?)

    /**
     * Streams the response with [ImageLoaderPolicy.MAX_DOWNLOAD_BYTES] as a hard cap; a
     * server advertising or actually sending more is treated as a wrong URL, not as art.
     */
    private fun download(url: String): Downloaded? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 10000
            if (!ImageLoaderPolicy.acceptDownload(connection.contentLengthLong)) return null
            val contentType = connection.contentType
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
            return Downloaded(out.toByteArray(), contentType)
        } finally {
            connection.disconnect()
        }
    }
}
