package ru.mars.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

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
 * Hot-path hardening (render/stream):
 * - concurrent [load] calls for the SAME url share one in-flight fetch+decode
 *   ([inFlight] dedup): a chat/music list rebind no longer starts N duplicate
 *   downloads of one cover; the first requested target size wins, consistent
 *   with the URL-keyed bitmap cache;
 * - the per-view delivery job is cancelled when the view leaves the window
 *   ([DetachWatcher]), so recycled/detached views don't schedule pointless
 *   main-thread hops; the shared fetch itself keeps running to fill the cache.
 *
 * Covers are cached on three levels — memory, disk, network ([load] order):
 * - memory: the byte-based [LruCache] above, the only level [trimMemoryCache] touches;
 * - disk: [ImageDiskCache] under `cacheDir/covers`, keyed by `sha256(url)` and bounded by
 *   [ImageDiskCachePolicy] (32 MB, 14-day TTL, LRU eviction). It survives process death,
 *   LRU eviction and system trims, so a cold start no longer re-downloads the whole music
 *   list over mobile data. Disk reads happen inside the shared fetch, i.e. behind the
 *   in-flight dedup, so N views of one URL still cause one disk read and at most one request;
 * - network: only when the disk has no fresh copy; a successful response is written to disk
 *   before it is decoded, so the next cold start finds it.
 *
 * Disk cache is attached from [MarsApplication.onCreate] via [attachContext]. Without it
 * (unit tests, instrumentation without Application) the loader degrades to memory+network —
 * caching is an optimisation and must never break the image path.
 *
 * Cached bitmaps are keyed by URL only: the first requested target size wins for subsequent
 * differently-sized views of the same URL.
 */
object ImageLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = object : LruCache<String, Bitmap>(
        ImageLoaderPolicy.cacheBudgetBytes(Runtime.getRuntime().maxMemory())
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Один разделяемый fetch+decode на URL: дедупликация одновременных запросов. */
    private val inFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()

    /**
     * Привязывает дисковый кэш обложек к процессу. Вызывается один раз из
     * [MarsApplication.onCreate] — раньше любого экрана, который может показать обложку.
     * Идемпотентно: повторный вызов ничего не делает.
     */
    fun attachContext(context: Context) {
        ImageDiskCache.attach(context)
    }

    /**
     * Полная очистка кэша миниатюр под системным давлением памяти
     * (вызывает [MarsApplication] из onTrimMemory/onLowMemory). Битмапы
     * перезагружаются по URL при следующем показе — корректность не страдает.
     *
     * Чистится ТОЛЬКО память: дисковый кэш ([ImageDiskCache]) trim переживает осознанно.
     * Давление памяти — это про кучу процесса, а не про место на диске; стереть диск
     * значило бы после каждого trim снова качать обложки по мобильной сети, то есть
     * чинить проблему, ради которой диск и появился.
     */
    fun trimMemoryCache() {
        cache.evictAll()
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
        // Dedup: computeIfAbsent атомарен — второй load() того же URL не начнёт
        // повторную загрузку, а подождёт результат первой. Ключ — только URL:
        // targetPx первого запроса выигрывает (как и в URL-кэше битмапов).
        val fetchDeferred = inFlight.computeIfAbsent(url) { key ->
            scope.async {
                val bitmap = fetch(key, targetPx)
                if (bitmap != null) cache.put(key, bitmap)
                // Сначала кладём в кэш, потом снимаем in-flight: новый load()
                // между этими шагами уже попадёт в кэш, а не в новый fetch.
                inFlight.remove(key)
                bitmap
            }
        }
        val deliveryJob = scope.launch {
            val bitmap = fetchDeferred.await()
            withContext(Dispatchers.Main) {
                // Защита от stale async result и утечки: ImageView держится только через
                // WeakReference; если view собрана сборщиком или переиспользована (tag
                // указывает на другой URL), результат просто выбрасывается.
                val view = viewRef.get() ?: return@withContext
                if (view.tag != url) return@withContext
                if (bitmap != null) view.setImageBitmap(bitmap) else view.visibility = View.GONE
            }
        }
        watchDetach(imageView, deliveryJob)
    }

    /**
     * Отменяет ДОСТАВКУ результата (не общий fetch — он кормит кэш и другие view),
     * когда view уходит из окна: recycle списка/закрытие экрана больше не
     * планирует main-thread hop впустую. Watcher хранится в именованном tag-слоте
     * и один на view: повторные load() лишь обновляют job, слушатели не копятся.
     */
    private fun watchDetach(view: View, job: Job) {
        val existing = view.getTag(R.id.mars_image_load_watcher) as? DetachWatcher
        if (existing != null) {
            existing.job = job
            return
        }
        val watcher = DetachWatcher(job)
        view.setTag(R.id.mars_image_load_watcher, watcher)
        view.addOnAttachStateChangeListener(watcher)
    }

    private class DetachWatcher(@Volatile var job: Job?) : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit

        override fun onViewDetachedFromWindow(v: View) {
            job?.cancel()
            job = null
            v.removeOnAttachStateChangeListener(this)
            v.setTag(R.id.mars_image_load_watcher, null)
        }
    }

    /**
     * Память → диск → сеть. Диск читается внутри общего fetch, то есть уже за дедупом
     * in-flight: N вьюх одного URL дают одно чтение диска и максимум один запрос.
     *
     * Сетевой ответ пишется на диск только после УСПЕШНОГО декодирования: иначе битые
     * байты (обрезанный ответ, HTML ошибки) осели бы в кэше на весь TTL и ломали показ
     * обложки до его истечения. Байты, которые не декодировались из кэша, удаляются —
     * запись считается испорченной и перезапрашивается.
     */
    private fun fetch(url: String, targetPx: Int): Bitmap? = runCatching {
        val cached = ImageDiskCache.read(url)
        if (cached != null) {
            val fromDisk = decode(cached, targetPx)
            if (fromDisk != null) return@runCatching fromDisk
            ImageDiskCache.remove(url)
        }
        val downloaded = download(url) ?: return@runCatching null
        val bitmap = decode(downloaded.bytes, targetPx) ?: return@runCatching null
        ImageDiskCache.write(url, downloaded.contentType, downloaded.bytes)
        bitmap
    }.getOrNull()

    /** Двухпроходное декодирование in-memory payload: сначала bounds, затем downsampling. */
    private fun decode(bytes: ByteArray, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageLoaderPolicy.sampleSize(bounds.outWidth, bounds.outHeight, targetPx)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** Сетевой ответ: байты плюс `Content-Type` — по нему дисковый кэш выбирает расширение. */
    private class Downloaded(val bytes: ByteArray, val contentType: String?)

    /**
     * Streams the response into memory with a hard [ImageLoaderPolicy.MAX_DOWNLOAD_BYTES]
     * cap; returns null when the server advertises or actually sends more than the cap.
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
