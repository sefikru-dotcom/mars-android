package ru.mars.mobile.widget

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.RemoteViews
import ru.mars.mobile.MarsMusicPlayer
import ru.mars.mobile.MusicA11yPolicy
import ru.mars.mobile.MusicActivity
import ru.mars.mobile.R
import ru.mars.mobile.ThumbnailUrlResolver
import ru.mars.mobile.service.MarsMusicService
import ru.mars.mobile.service.MusicNotificationPolicy
import ru.mars.network.model.MusicTrack
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Виджет домашнего экрана «Музыка Марса» (4x2): обложка, название, исполнитель и
 * компактный транспорт prev / play-pause / next.
 *
 * ТРИ ПРАВИЛА, которых держится вся реализация:
 *
 * 1. Виджет НЕ владеет воспроизведением. Своего плеера и своего сервиса здесь нет:
 *    кнопки шлют уже существующие [MusicNotificationPolicy.ACTION_*] в
 *    [MarsMusicService] через `PendingIntent.getService` — ровно те же строки,
 *    которые несёт кнопка в шторке, поэтому маршрутизация в
 *    `MarsMusicService.onStartCommand` не меняется и дублей action-строк не возникает.
 *    Тап по обложке или по тексту открывает [MusicActivity] через
 *    `PendingIntent.getActivity`.
 *
 * 2. Состояние приходит по существующему SPI. [MarsMusicPlayer] — процессный
 *    синглтон, который рассылает [MarsMusicPlayer.MarsMusicListener.onStateChanged]
 *    смену трека и play/pause; тот же интерфейс слушают MusicActivity, ChatActivity и
 *    сам сервис. [MusicWidgetHost] регистрирует слушателя на всё время жизни виджета
 *    (`onEnabled` / первый `onUpdate` → `onDisabled`) и по событию перерисовывает ВСЕ
 *    экземпляры виджета через [AppWidgetManager]. Сервис и policy-файлы музыки не
 *    правились: это чужая зона, и правка там не нужна — SPI уже публичный.
 *    Прогресс (`onProgress`, тик ~500 мс) виджет намеренно глотает: перерисовка
 *    RemoteViews на каждом тике — это чужие байты через Binder и лишний расход
 *    батареи, а на виджете нет скраббера, которому нужно позиция.
 *
 * 3. Обложка грузится обычным [HttpURLConnection] в фоне и попадает в RemoteViews
 *    как Bitmap, даунсэмпленный до [MusicWidgetContent.COVER_TARGET_PX]: библиотек
 *    загрузки изображений (Glide/Coil) в проекте нет, и тянуть зависимость ради
 *    виджета нельзя. Ошибка загрузки глотается — остаётся заглушка `ic_music`.
 *    Битмап в RemoteViews идёт через Binder целиком, поэтому размер ограничен:
 *    192 px по стороне — это ~150 КБ на транзакцию, безопасно.
 *
 * Периодических будильников нет: `updatePeriodMillis="0"` в
 * `res/xml/music_widget_info.xml`, обновление только по событиям плеера.
 */
class MusicWidgetProvider : AppWidgetProvider() {

    /**
     * Первое появление виджета: подписываемся на плеер. Слушатель в
     * [MarsMusicPlayer.registerListener] получает текущее состояние сразу, поэтому
     * виджет показывает актуальный трек ещё до первого `onUpdate`.
     */
    override fun onEnabled(context: Context) {
        MusicWidgetHost.attach(context.applicationContext)
    }

    /**
     * `onUpdate` — системный запрос перерисовки (добавление виджета, смена размера,
     * перезагрузка лаунчера). Сначала обеспечиваем подписку, затем РИСУЕМ по тем id,
     * что прислала система: немедленного колбэка `registerListener` недостаточно —
     * только что добавленный id может ещё не попадать в `getAppWidgetIds`, и виджет
     * остался бы с initialLayout до следующего события плеера. Лишний
     * `updateAppWidget` на этом пути дешевле пустого виджета.
     */
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val app = context.applicationContext
        MusicWidgetHost.attach(app)
        MusicWidgetHost.render(app, appWidgetIds)
    }

    /** Последний экземпляр снят: отписываемся, чтобы не держать слушателя впустую. */
    override fun onDisabled(context: Context) {
        MusicWidgetHost.detach()
    }
}

/**
 * Состояние виджета в процессе: application-контекст, подписка на плеер и отрисовка.
 *
 * Отдельный объект, а не поля [MusicWidgetProvider]: экземпляр провайдера создаётся
 * системой на каждый broadcast и умирает вместе с ним, а слушатель обязан жить
 * столько же, сколько виджет на экране.
 */
internal object MusicWidgetHost {

    /** Application-контекст (не Activity): держим только его, утечки нет. */
    @Volatile
    private var appContext: Context? = null

    /** True пока слушатель зарегистрирован в [MarsMusicPlayer]. */
    @Volatile
    private var listening = false

    private val listener = object : MarsMusicPlayer.MarsMusicListener {
        override fun onStateChanged(track: MusicTrack?, isPlaying: Boolean) {
            val context = appContext ?: return
            renderAll(context)
        }

        /** Виджет не показывает позицию: тик прогресса — no-op (см. KDoc класса). */
        override fun onProgress(currentMs: Int, totalMs: Int) = Unit
    }

    /**
     * Подписывает на плеер, если ещё не подписан. Повторный вызов безопасен и ничего
     * не дублирует: `listening` гасит вторую регистрацию того же слушателя.
     */
    @Synchronized
    fun attach(context: Context) {
        appContext = context
        if (listening) return
        listening = true
        // Последний шаг: регистрация сразу отдаёт текущее состояние в onStateChanged,
        // поэтому уже поставленные виджеты обновляются без отдельного прохода.
        MarsMusicPlayer.registerListener(listener)
    }

    /** Снимает подписку (последний виджет удалён с экрана). */
    @Synchronized
    fun detach() {
        if (!listening) return
        listening = false
        MarsMusicPlayer.unregisterListener(listener)
        appContext = null
    }

    /** Перерисовывает все существующие экземпляры виджета. */
    fun renderAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
        if (ids.isEmpty()) return
        render(context, ids)
    }

    /**
     * Собирает RemoteViews по текущему снимку плеера и рассылает его на [ids],
     * затем просит [WidgetCoverLoader] догрузить обложку.
     *
     * RemoteViews здесь ВСЕГДА полный (текст + транспорт + текущая обложка): только
     * так его можно отдать в `updateAppWidget`. Обновление «только картинки»
     * невозможно — `updateAppWidget` заменяет закэшированное представление целиком,
     * и частичный RemoteViews со `setImageViewBitmap` стёр бы название и кнопки.
     * `partiallyUpdateAppWidget` для битмапов Google тоже запрещает (копии остаются
     * в памяти system_server до следующего полного обновления). Поэтому доехавшая
     * из сети обложка приводит сюда же — на полную перерисовку.
     */
    fun render(context: Context, ids: IntArray) {
        if (ids.isEmpty()) return
        val manager = AppWidgetManager.getInstance(context) ?: return
        val track = MarsMusicPlayer.currentTrack
        val isPlaying = MarsMusicPlayer.isPlaying
        manager.updateAppWidget(ids, buildViews(context, track, isPlaying))
        WidgetCoverLoader.request(context, MusicWidgetContent.coverUrl(track?.thumbnail))
    }

    /**
     * Текст + транспорт + обложка. Клики ведут либо в сервис (транспорт), либо в
     * MusicActivity (обложка и текстовый блок) — никакой логики проигрывания здесь нет.
     */
    private fun buildViews(context: Context, track: MusicTrack?, isPlaying: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.music_widget)
        val hasTrack = track != null

        views.setTextViewText(R.id.widget_title, MusicWidgetContent.title(track?.title, hasTrack))
        views.setTextViewText(
            R.id.widget_artist,
            MusicWidgetContent.subtitle(track?.artist, hasTrack),
        )

        // Обложка: из кэша, а если её ещё нет — заглушка ic_music (не картинка
        // предыдущего трека: чужая обложка хуже честной заглушки).
        WidgetCoverLoader.applyTo(views, MusicWidgetContent.coverUrl(track?.thumbnail))

        // Иконка читает ДЕЙСТВИЕ кнопки: играет — рисуем паузу, иначе треугольник play.
        views.setImageViewResource(
            R.id.widget_play,
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
        )
        views.setContentDescription(
            R.id.widget_play,
            MusicWidgetContent.playPauseDescription(isPlaying),
        )

        views.setOnClickPendingIntent(
            R.id.widget_play,
            transportIntent(
                context,
                MusicWidgetContent.playPauseAction(isPlaying),
                REQUEST_PLAY_PAUSE,
            ),
        )
        views.setOnClickPendingIntent(
            R.id.widget_prev,
            transportIntent(context, MusicNotificationPolicy.ACTION_PREV, REQUEST_PREV),
        )
        views.setOnClickPendingIntent(
            R.id.widget_next,
            transportIntent(context, MusicNotificationPolicy.ACTION_NEXT, REQUEST_NEXT),
        )

        val openIntent = openPlayerIntent(context)
        views.setOnClickPendingIntent(R.id.widget_cover, openIntent)
        views.setOnClickPendingIntent(R.id.widget_text, openIntent)
        return views
    }

    /**
     * Транспортная кнопка: сервисный intent с уже существующей action-строкой
     * [MusicNotificationPolicy.ACTION_*]. `getService`, а не собственный broadcast:
     * команда обязана попасть в тот же `onStartCommand`, что и кнопка в шторке.
     */
    private fun transportIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, MarsMusicService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Тап по обложке/тексту открывает сам экран музыки. */
    // TAP_INTENT_FLAGS mirrors Intent.FLAG_ACTIVITY_* (framework-free policy, JVM-testable);
    // suppressed exactly like MarsMusicService.buildNotification does for the same constant.
    @SuppressLint("WrongConstant")
    private fun openPlayerIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            // Те же флаги, что у тапа по уведомлению: без CLEAR_TOP|SINGLE_TOP каждый
            // тап по виджету ставил бы ещё один MusicActivity в стек — «назад»
            // потом прокручивал бы копии плеера вместо выхода из раздела.
            Intent(context, MusicActivity::class.java)
                .addFlags(MusicNotificationPolicy.TAP_INTENT_FLAGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * Коды запросов PendingIntent. Отдельный диапазон (10+) от сервисных (0..4 —
     * ordinal'ы [MusicNotificationPolicy.ActionType]): PendingIntent различается парой
     * requestCode + intent, и свои коды гарантируют, что кнопка виджета не перезапишет
     * кнопку шторки с тем же действием.
     */
    private const val REQUEST_OPEN = 10
    private const val REQUEST_PLAY_PAUSE = 11
    private const val REQUEST_PREV = 12
    private const val REQUEST_NEXT = 13
}

/**
 * Обложка текущего трека для RemoteViews.
 *
 * Почему Bitmap, а не `setImageViewUri`: URI заставил бы ХОСТ (лаунчер) скачивать
 * картинку сам — со своей сетью, таймаутами и без нашего даунсэмплинга; `file://`
 * URI между процессами на targetSdk 34 ещё и требует FileProvider с грантом на
 * каждый клик. Здесь сеть и декодирование остаются в нашем процессе, а в RemoteViews
 * уезжает готовый маленький битмап.
 *
 * Модель обновлений: лоадер НИКОГДА не правит виджет сам. Он только наполняет кэш
 * битмапов; `applyTo` вставляет из кэша обложку или заглушку в ПОЛНЫЙ RemoteViews,
 * который собирает [MusicWidgetHost.render], а доехавшая из сети картинка просит у
 * хоста полную перерисовку. Иначе пришлось бы слать частичный RemoteViews со
 * `setImageViewBitmap`, а `updateAppWidget` заменяет представление целиком — текст и
 * кнопки стёрлись бы.
 *
 * Кэш — несколько битмапов по URL в памяти процесса: переключение play/pause не
 * должно заново ходить в сеть за той же обложкой. Кэш намеренно крошечный (обложки
 * крошечные): виджет не участвует в прокрутке, ему хватает текущего трека и соседей.
 */
internal object WidgetCoverLoader {

    private const val CACHE_ENTRIES = 8
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mars-widget-cover").apply { isDaemon = true }
    }

    /** URL, которые уже качаются: повторный запрос того же адреса не плодит поток. */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val cache = LruCache<String, Bitmap>(CACHE_ENTRIES)

    /**
     * Кладёт обложку в [views]: из кэша — битмап, иначе заглушка `ic_music`.
     * null в [url] (у трека нет обложки) — сразу заглушка.
     */
    @Synchronized
    fun applyTo(views: RemoteViews, url: String?) {
        val bitmap = url?.let { cache.get(it) }
        if (bitmap != null) {
            views.setImageViewBitmap(R.id.widget_cover, bitmap)
        } else {
            views.setImageViewResource(R.id.widget_cover, R.drawable.ic_music)
        }
    }

    /**
     * Просит догрузить обложку [url] в фоне, если её ещё нет в кэше. Уже идущая
     * загрузка того же URL повторно не запускается. null — делать нечего.
     */
    fun request(context: Context, url: String?) {
        if (url == null) return
        if (cache.get(url) != null) return
        if (!inFlight.add(url)) return
        val app = context.applicationContext
        executor.execute { fetch(app, url) }
    }

    /**
     * Скачивает и декодирует обложку и просит у хоста полную перерисовку всех
     * виджетов: экземпляров может быть несколько, а трек у них общий (процессный
     * плеер). Ошибка загрузки глотается — на месте обложки уже стоит заглушка.
     */
    private fun fetch(context: Context, url: String) {
        val bitmap = runCatching { download(url) }.getOrNull()
        inFlight.remove(url)
        if (bitmap == null) return
        cache.put(url, bitmap)
        // Пока качали, трек мог смениться: тогда применяет уже следующий проход.
        if (MusicWidgetContent.coverUrl(MarsMusicPlayer.currentTrack?.thumbnail) != url) return
        MusicWidgetHost.renderAll(context)
    }

    /** HTTP GET с жёсткими таймаутами, потолком байт и даунсэмплингом до цели. */
    private fun download(url: String): Bitmap? {
        val connection = (URL(url).openConnection() as? HttpURLConnection) ?: return null
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        try {
            if (connection.responseCode !in 200..299) return null
            // Content-Length бывает -1 (chunked): потолок проверяется ещё и при чтении.
            if (connection.contentLengthLong > MusicWidgetContent.COVER_MAX_BYTES) return null
            val bytes = connection.inputStream.use { readCapped(it) } ?: return null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sample = MusicWidgetContent.inSampleSizeFor(
                bounds.outWidth,
                bounds.outHeight,
                MusicWidgetContent.COVER_TARGET_PX,
            )
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } finally {
            connection.disconnect()
        }
    }

    /** Читает тело с потолком [MusicWidgetContent.COVER_MAX_BYTES]; больше — отказ. */
    private fun readCapped(stream: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            if (out.size() + read > MusicWidgetContent.COVER_MAX_BYTES) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}

/**
 * Чистое содержимое виджета — без Android-типов, поэтому проверяется обычными
 * JVM-тестами (MusicWidgetContentTest), а не только глазами на эмуляторе.
 *
 * Здесь же единственное место, где решается, какая action-строка уедет в сервис и
 * каким текстом TalkBack назовёт кнопку: строки берутся из существующих
 * [MusicNotificationPolicy] (транспорт шторки) и [MusicA11yPolicy] (озвучка главного
 * play/pause), а не дублируются литералами. Разъехаться виджету и экрану музыки
 * негде: источник у них один.
 */
internal object MusicWidgetContent {

    /** Заголовок, когда трека нет вовсе. */
    const val NO_TRACK_TITLE = "Марс Музыка"

    /** Подзаголовок в простое: плеер ничего не держит. */
    const val NO_TRACK_SUBTITLE = "Ничего не играет"

    /** Трек есть, но название пустое (сервер отдал пустую строку). */
    const val UNKNOWN_TITLE = "Неизвестный трек"

    /** Трек есть, но исполнитель пустой. */
    const val UNKNOWN_ARTIST = "Неизвестный исполнитель"

    /**
     * Сторона обложки в пикселях после даунсэмплинга. 64dp на экране 3x — это 192 px;
     * столько же держит кэш, и столько уезжает в RemoteViews (~150 КБ на транзакцию).
     */
    const val COVER_TARGET_PX = 192

    /** Потолок скачиваемой обложки: больше — это не обложка, а мусор или подмена. */
    const val COVER_MAX_BYTES = 1_500_000

    /** Название трека с откатом на [UNKNOWN_TITLE] / [NO_TRACK_TITLE]. */
    fun title(raw: String?, hasTrack: Boolean): String {
        val value = raw?.trim().orEmpty()
        return when {
            value.isNotEmpty() -> value
            hasTrack -> UNKNOWN_TITLE
            else -> NO_TRACK_TITLE
        }
    }

    /** Исполнитель: пустой артист у существующего трека — [UNKNOWN_ARTIST], не «нет трека». */
    fun subtitle(raw: String?, hasTrack: Boolean): String {
        val value = raw?.trim().orEmpty()
        return when {
            value.isNotEmpty() -> value
            hasTrack -> UNKNOWN_ARTIST
            else -> NO_TRACK_SUBTITLE
        }
    }

    /**
     * Action для кнопки play/pause: играет — пауза, иначе воспроизведение. Строки —
     * константы [MusicNotificationPolicy], те же, что несёт кнопка шторки.
     */
    fun playPauseAction(isPlaying: Boolean): String =
        if (isPlaying) MusicNotificationPolicy.ACTION_PAUSE else MusicNotificationPolicy.ACTION_PLAY

    /** Подпись TalkBack по ДЕЙСТВИЮ кнопки (паритет с экраном музыки и мини-плеером). */
    fun playPauseDescription(isPlaying: Boolean): String =
        MusicA11yPolicy.playPauseAction(isPlaying)

    /**
     * Абсолютный URL обложки (сервер отдаёт относительный `/music/api/thumb?v=…`).
     * Резолвит существующий [ThumbnailUrlResolver]: схема проверяется им же, поэтому
     * `javascript:`/`data:` сюда не пролезут.
     */
    fun coverUrl(raw: String?): String? = ThumbnailUrlResolver.resolve(raw)

    /**
     * Степень двойного уменьшения для [BitmapFactory.Options.inSampleSize]: самая
     * большая, при которой обе стороны ещё не меньше [targetPx]. Невалидные размеры
     * (0 и меньше — битый заголовок картинки) дают 1, то есть «декодируй как есть».
     */
    fun inSampleSizeFor(width: Int, height: Int, targetPx: Int): Int {
        if (width <= 0 || height <= 0 || targetPx <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= targetPx && height / (sample * 2) >= targetPx) {
            sample *= 2
        }
        return sample
    }
}
