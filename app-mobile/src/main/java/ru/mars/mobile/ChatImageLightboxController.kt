package ru.mars.mobile

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fullscreen image viewer (lightbox) — слой UI-контроллеров экрана.
 *
 * Владеет ТОЛЬКО показом картинки: построением/показом [Dialog], backdrop-scrim,
 * [ImageView], строкой прогресса/ошибки и кнопкой закрытия, а также bounded-загрузкой
 * и декодированием bitmap. Резолв URL и эвристика «это картинка» остаются в чистых
 * [ImageLightboxPolicy]/[ThumbnailUrlResolver]; цвета и кнопки — в [MarsTheme]/[MarsGradients].
 *
 * Память: payload ограничен [MAX_IMAGE_BYTES] (поток обрывается на превышении),
 * размер кадра — bounds-decode до [MAX_IMAGE_DIMENSION] px (inSampleSize, кратный 2).
 * Кэша битмапов здесь нет намеренно: его не было и в прежней реализации.
 *
 * Lifecycle: загрузка идёт в переданном [scope] (у экрана — lifecycleScope) и на
 * [Dispatchers.IO]; результат публикуется только пока диалог ещё показан
 * (`Dialog.isShowing`) и экран жив, поэтому закрытый диалог не получает bitmap.
 */
class ChatImageLightboxController(
    private val context: Context,
    private val scope: CoroutineScope,
    /** База резолва относительных путей (у экрана — [ChatActivity.BASE_URL]). */
    private val baseUrl: String,
    /** Сообщение для статус-строки экрана, когда картинку открыть/загрузить нельзя. */
    private val onError: (String) -> Unit = {},
) {

    /**
     * Открывает картинку по сырому URL: резолв относительно [baseUrl], полноэкранный
     * диалог, асинхронная загрузка. Недоступный URL — [ERROR_UNRESOLVED] в [onError];
     * сбой загрузки — [ERROR_LOAD_FAILED] прямо в диалоге; тап по фону/крестик — закрытие.
     */
    fun open(rawUrl: String) {
        val url = ImageLightboxPolicy.resolveUrl(rawUrl, baseUrl)
        if (url == null) {
            onError(ERROR_UNRESOLVED)
            return
        }
        val dialog = Dialog(context)
        val root = FrameLayout(context).apply {
            setBackgroundColor(MarsTheme.LIGHTBOX_SCRIM)
            fitsSystemWindows = true
            setOnClickListener { dialog.dismiss() }
        }
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = A11Y_IMAGE
        }
        val progress = TextView(context).apply {
            text = LOADING_LABEL
            setTextColor(MarsTheme.TEXT_MUTED)
            textSize = 13f
            gravity = Gravity.CENTER
        }
        root.addView(
            image,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            progress,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            )
        )
        val closeBtn = MarsTheme.circleIconButton(
            context, R.drawable.ic_close, A11Y_CLOSE,
            bg = MarsTheme.SURFACE, iconTint = MarsTheme.TEXT_STRONG, sizeDp = BUTTON_DP
        ).apply { setOnClickListener { dialog.dismiss() } }
        root.addView(
            closeBtn,
            FrameLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(16)
                marginEnd = dp(16)
            }
        )
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setDimAmount(1f)
        }
        dialog.show()
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { fetchBitmap(url) }
            // Диалог мог быть закрыт пользователем (или экран уничтожен) пока шла загрузка:
            // закрытому диалогу bitmap не публикуем, а на уничтоженном экране гасим показ.
            if (!dialog.isShowing || context.isHostDestroyed()) return@launch
            if (bitmap != null) {
                image.setImageBitmap(bitmap)
                progress.visibility = View.GONE
            } else {
                progress.text = ERROR_LOAD_FAILED
            }
        }
    }

    /** Bounded bitmap fetch: ≤[MAX_IMAGE_BYTES] payload, downscale выше [MAX_IMAGE_DIMENSION] px. */
    private fun fetchBitmap(url: String): Bitmap? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            val bytes = connection.inputStream.use { stream ->
                readBounded(stream, MAX_IMAGE_BYTES)
            } ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_IMAGE_DIMENSION)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun dp(value: Int) = MarsTheme.dp(context, value)

    /** Экран уже уничтожен: публиковать bitmap в его окно нельзя (leak/краш). */
    private fun Context.isHostDestroyed(): Boolean =
        (this as? android.app.Activity)?.isDestroyed ?: false

    companion object {
        /** Payload cap для просмотрщика: больше не читаем (memory discipline). */
        const val MAX_IMAGE_BYTES = 25L * 1024 * 1024

        /** Максимальная сторона кадра после downsample. */
        const val MAX_IMAGE_DIMENSION = 4096

        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 15000
        private const val BUTTON_DP = 40

        const val LOADING_LABEL = "Загрузка…"
        const val ERROR_UNRESOLVED = "Изображение недоступно"
        const val ERROR_LOAD_FAILED = "Не удалось загрузить изображение"
        const val A11Y_IMAGE = "Просмотр изображения"
        const val A11Y_CLOSE = "Закрыть просмотр"

        /**
         * Читает поток целиком, но не больше [maxBytes]: на превышении возвращает null
         * (гигантский payload отбрасывается до декодирования, а не после OOM).
         */
        fun readBounded(stream: InputStream, maxBytes: Long): ByteArray? {
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(chunk)
                if (read <= 0) break
                total += read
                if (total > maxBytes) return null
                buffer.write(chunk, 0, read)
            }
            return buffer.toByteArray()
        }

        /**
         * Множитель downsample (степень двойки) так, чтобы обе стороны уложились
         * в [maxDimension]. 1 — уменьшать не нужно; невалидные размеры (0/отрицательные,
         * нераспознанный формат) тоже дают 1 — решает уже сам `decodeByteArray`.
         */
        fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
            if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
            var sample = 1
            while (width / sample > maxDimension || height / sample > maxDimension) {
                sample *= 2
            }
            return sample
        }
    }
}
