package ru.mars.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Процесс-глобальный кеш обложек музыкального экрана ТВ (порт mobile
 * [ru.mars.mobile.ImageLoader] — без внешних библиотек: HttpURLConnection + BitmapFactory;
 * в app-tv новые зависимости запрещены).
 *
 * Инварианты безопасности по памяти (числовые правила — в чистой
 * [TvImageLoaderPolicy], JVM-тесты):
 * - LruCache считается по БАЙТАМ (bitmap.byteCount), бюджет — 1/8 кучи, зажат в 2..24 МБ,
 *   так что крупные обложки не ломают учёт кеша;
 * - каждое декодирование даунсэмплится под размер вьюхи (inSampleSize — степень двойки);
 * - слишком большие загрузки отвергаются и по Content-Length, и по потоковому лимиту;
 * - фоновая задача держит ТОЛЬКО [WeakReference] на ImageView: медленный запрос не
 *   пинит вьюху (а через неё — Activity) после закрытия экрана.
 *
 * Отличие от mobile-версии: вместо корутин — фоновый [Executor] и [Handler] главного
 * потока (стиль app-tv: TvMusicScreenController/TvUiDispatcher, никаких новых зависимостей).
 *
 * Известное ограничение: дискового кеша нет — перезагрузка после смерти процесса или
 * LRU-эвикции. Битмапы кешируются только по URL: первый запрошенный целевой размер
 * побеждает для последующих вьюх другого размера с тем же URL.
 */
object TvImageLoader {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tv-image-loader").apply { isDaemon = true }
    }

    private val cache = object : LruCache<String, Bitmap>(
        TvImageLoaderPolicy.cacheBudgetBytes(Runtime.getRuntime().maxMemory())
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * Грузит обложку по серверному [rawUrl] (относительный `/music/api/thumb?v=ID`
     * резолвится [TvThumbnailUrlResolver] от origin сайта) в [imageView].
     * Нерезолвящийся/пустой URL прячет вьюху; результат асинхронной загрузки
     * применяется только если вьюха жива и её tag всё ещё указывает на этот URL
     * (защита от stale-результата при перестроении списка).
     */
    fun load(rawUrl: String?, imageView: ImageView) {
        val url = TvThumbnailUrlResolver.resolve(rawUrl)
        if (url == null) {
            imageView.tag = null
            imageView.setImageDrawable(null)
            imageView.visibility = View.GONE
            return
        }
        imageView.visibility = View.VISIBLE
        imageView.tag = url
        cache.get(url)?.let { imageView.setImageBitmap(it); return }
        // Целевой размер декодирования читается на главном потоке до переиспользования
        // вьюхи; 0x0 (ещё не размечена) уходит в TvImageLoaderPolicy.DEFAULT_TARGET_PX.
        val targetPx = TvImageLoaderPolicy.targetSizePx(imageView.width, imageView.height)
        val viewRef = WeakReference(imageView)
        executor.execute {
            val bitmap = fetch(url, targetPx)
            if (bitmap != null) cache.put(url, bitmap)
            mainHandler.post {
                // Защита от stale async result и утечки: ImageView держится только через
                // WeakReference; если вьюха собрана сборщиком или переиспользована
                // (tag указывает на другой URL), результат просто выбрасывается.
                val view = viewRef.get() ?: return@post
                if (view.tag != url) return@post
                if (bitmap != null) view.setImageBitmap(bitmap) else view.visibility = View.GONE
            }
        }
    }

    private fun fetch(url: String, targetPx: Int): Bitmap? = runCatching {
        val bytes = download(url) ?: return@runCatching null
        // Двухпроходное декодирование in-memory payload: сначала bounds, затем downsampling.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = TvImageLoaderPolicy.sampleSize(bounds.outWidth, bounds.outHeight, targetPx)
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()

    /**
     * Стримит ответ в память с жёстким лимитом [TvImageLoaderPolicy.MAX_DOWNLOAD_BYTES];
     * возвращает null, если сервер объявил или реально прислал больше лимита.
     */
    private fun download(url: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 10000
            if (!TvImageLoaderPolicy.acceptDownload(connection.contentLengthLong)) return null
            val cap = TvImageLoaderPolicy.MAX_DOWNLOAD_BYTES
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
