package ru.mars.mobile.tts

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Однократная подготовка офлайн-синтеза: единственное место в приложении, которое зовёт
 * [OfflineTtsPort.prepare].
 *
 * ## Зачем отдельный класс
 * `prepare()` скачивает 21 МиБ модели и поднимает нативный движок. Пока его не позвали,
 * `isReady()` всегда `false` и «Озвучить» молчит. Звать его из каждой реплики нельзя:
 * это 21 МиБ трафика и секунды ожидания на каждое нажатие. Поэтому подготовка живёт здесь:
 * 1. `prepare()` вызывается **ровно один раз за жизнь процесса** — успех запоминается
 *    ([ready]), и повторный [warmUp] возвращает `true` мгновенно, не трогая сеть;
 * 2. параллельные вызовы сериализуются [Mutex]: второй ждёт первый и получает его результат,
 *    а не качает модель второй раз;
 * 3. прогресс скачивания уходит наружу колбэком `(downloaded, total) -> Unit` — экран
 *    показывает «Готовлю голос… N %»;
 * 4. отказ возвращается **причиной строкой** ([WarmUpResult.Failed.reason]) и запоминается
 *    в [lastFailure]; успех при отказе не заявляется никогда;
 * 5. [isReady] — дешёвая проверка без сети (флаг + `port.isReady()`).
 *
 * ## Что именно качает
 * Свой загрузчик здесь не нужен и запрещён: `prepare()` у [OfflineTtsBackend] уже делает
 * `OfflineTtsModel.ensure()` (HTTPS -> `.part` -> SHA-256 -> staging -> атомарная установка)
 * и создаёт движок. Этот класс только оборачивает его политикой «один раз».
 *
 * ## Прогресс
 * `OfflineTtsModel` принимает `onProgress` в конструкторе, поэтому прогресс виден, только
 * если модель создана с релеем. Такую связку собирает [forApp]: модель -> [ProgressRelay]
 * -> бэкенд. Если координатор создаёт бэкенд сам (`OfflineTtsBackend(context)`), прогресса
 * не будет (скачивание всё равно произойдёт и сработает), а [isReady]/[lastFailure] —
 * будут. Колбэк приходит **с фонового потока** загрузки: экран обязан переложить его на
 * main (`runOnUiThread`), а не трогать View напрямую.
 *
 * ## Отмена
 * `CancellationException` не глотается: она уходит вызывающему, состояние не помечается ни
 * успехом, ни отказом. Скачивание, начатое [OfflineTtsBackend.prepare], при этом не рвётся
 * посреди файла — модель доустанавливается целиком, и следующий [warmUp] поднимет движок
 * уже без сети.
 *
 * ## Повтор после отказа
 * Отказ запоминается вместе с временем. Следующий [warmUp] в течение
 * [RETRY_COOLDOWN_NANOS] (60 с) возвращает ту же причину, не пытаясь качать снова — иначе
 * каждое «Озвучить» при отсутствии сети заново тянуло бы 21 МиБ. Явное «Повторить» на
 * экране зовёт [retry], который паузу обходит.
 */
class OfflineTtsWarmup(
    /** Порт синтеза. Единственный метод, который здесь вызывается, — `prepare()`. */
    val port: OfflineTtsPort,
    /**
     * Установщик модели — только для ЧТЕНИЯ состояния ([OfflineTtsModel.isInstalled]).
     * `null` допустим: тогда [isReady] опирается на порт, а причина отказа будет общей.
     */
    private val model: OfflineTtsModel? = null,
    /** Проверки окружения для человеческой причины отказа. `null` — без диагностики. */
    private val diagnostics: WarmUpDiagnostics? = null,
    /**
     * Релей прогресса. Обязан быть тем же объектом, который передан в `onProgress`
     * у [OfflineTtsModel] (см. [forApp]), иначе колбэк [warmUp] не получит событий.
     */
    private val relay: ProgressRelay = ProgressRelay(),
    /** Монотонные часы для паузы между попытками. Вынесены ради тестируемости. */
    private val clockNanos: () -> Long = System::nanoTime,
) {

    /** Подготовка уже завершилась успехом: сеть больше не нужна. */
    @Volatile
    private var ready: Boolean = false

    /** Причина последнего отказа — та же строка, что вернул [warmUp]. */
    @Volatile
    var lastFailure: String? = null
        private set

    /** Момент отказа по [clockNanos] — для паузы между попытками. */
    @Volatile
    private var failedAtNanos: Long = 0L

    /** Порт освобождён: подготовка невозможна до пересоздания бэкенда. */
    @Volatile
    private var closed: Boolean = false

    /** Сериализует подготовку: второй вызов ждёт первый, а не качает второй раз. */
    private val mutex = Mutex()

    /**
     * Быстрая готовность без сети.
     *
     * `true`, если движок уже поднят ([OfflineTtsPort.isReady]) — своим [warmUp] или
     * кем-то ещё (например, сервисом). Ничего не скачивает и не блокирует.
     */
    fun isReady(): Boolean = !closed && (ready || port.isReady())

    /**
     * Подготовить синтез. `true` — движок готов и [isReady] начнёт отдавать `true`.
     *
     * Повторный вызов после успеха возвращает `true` сразу, не трогая сеть.
     *
     * @param onProgress прогресс скачивания `(downloaded, total)` в байтах; приходит с
     *   фонового потока, экран перекладывает на main. `null` — прогресс не нужен.
     */
    suspend fun warmUp(onProgress: ((Long, Long) -> Unit)? = null): Boolean =
        warmUpResult(onProgress = onProgress) is WarmUpResult.Ready

    /**
     * Подготовить синтез, обходя паузу после отказа — для кнопки «Повторить».
     *
     * Пауза нужна, чтобы каждое «Озвучить» при мёртвой сети не тянуло 21 МиБ заново;
     * явное действие человека её снимает.
     */
    suspend fun retry(onProgress: ((Long, Long) -> Unit)? = null): Boolean =
        warmUpResult(force = true, onProgress = onProgress) is WarmUpResult.Ready

    /**
     * То же, что [warmUp], но отказ приходит вместе с причиной для человека.
     *
     * Причина не выдумывается на пустом месте: `prepare()` возвращает только `Boolean`,
     * поэтому текст собирается из состояния окружения (сеть, свободное место, модель на
     * диске) и из исключения, если оно было.
     *
     * @param force игнорировать паузу после отказа.
     */
    suspend fun warmUpResult(
        force: Boolean = false,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): WarmUpResult {
        // Движок уже поднят (кем угодно) — prepare не нужен вовсе.
        if (isReady()) {
            ready = true
            lastFailure = null
            return WarmUpResult.Ready
        }
        if (closed) return WarmUpResult.Failed(REASON_RELEASED)
        cachedFailure(force)?.let { return WarmUpResult.Failed(it) }

        val detach = onProgress?.let(relay::attach)
        try {
            return mutex.withLock {
                // Пока ждали мьютекс, первый вызов мог всё сделать: повторяем проверки,
                // иначе два «Озвучить» подряд дали бы два скачивания.
                if (isReady()) {
                    ready = true
                    lastFailure = null
                    return@withLock WarmUpResult.Ready
                }
                if (closed) return@withLock WarmUpResult.Failed(REASON_RELEASED)
                cachedFailure(force)?.let { return@withLock WarmUpResult.Failed(it) }

                // Модели нет — сейчас начнётся скачивание: сразу отдаём 0 %, чтобы экран
                // показал «Готовлю голос…», не дожидаясь первого мегабайта.
                if (model?.isInstalled() != true) {
                    relay.emit(0L, OfflineTtsManifest.ARCHIVE_SIZE)
                }

                val prepared: Boolean
                try {
                    // Единственный вызов prepare() во всём приложении.
                    prepared = port.prepare()
                } catch (cancelled: CancellationException) {
                    // Отмену не глотаем и не считаем ни успехом, ни отказом.
                    throw cancelled
                } catch (error: Throwable) {
                    val reason = describe(error)
                    remember(reason)
                    return@withLock WarmUpResult.Failed(reason)
                }

                // `prepare()` мог ответить `true` и не поднять движок (гонка с release):
                // верим факту, а не возвращённому флагу.
                if (prepared && port.isReady()) {
                    ready = true
                    lastFailure = null
                    return@withLock WarmUpResult.Ready
                }

                val reason = if (prepared) REASON_ENGINE_NOT_UP else diagnose()
                remember(reason)
                WarmUpResult.Failed(reason)
            }
        } finally {
            detach?.invoke()
        }
    }

    /**
     * Освободить порт и закрыть подготовку. Повторный вызов безопасен.
     * После него [warmUp] отказывает: бэкенд освобождён навсегда.
     */
    fun release() {
        closed = true
        ready = false
        runCatching { port.release() }
    }

    /** Модель уже лежит на диске (значит, следующий `prepare` не пойдёт в сеть). */
    fun isModelInstalled(): Boolean = model?.isInstalled() == true

    // --- внутреннее -----------------------------------------------------------------------

    /** Причина отказа, если предыдущая попытка была недавно и обходить паузу не просили. */
    private fun cachedFailure(force: Boolean): String? {
        val reason = lastFailure ?: return null
        if (force) return null
        return if (clockNanos() - failedAtNanos < RETRY_COOLDOWN_NANOS) reason else null
    }

    private fun remember(reason: String) {
        lastFailure = reason
        failedAtNanos = clockNanos()
    }

    /** Текст для человека по состоянию окружения. Порядок проверок — от частого к редкому. */
    private fun diagnose(): String {
        val env = diagnostics
        if (env != null) {
            if (runCatching { env.hasNetwork() }.getOrNull() == false) return REASON_NO_NETWORK
            val free = runCatching { env.freeBytes() }.getOrNull()
            if (free != null && free in 0 until REQUIRED_FREE_BYTES) {
                return "Мало свободного места: голосу нужно около ${megabytes(REQUIRED_FREE_BYTES)} МБ, " +
                    "а свободно ${megabytes(free)} МБ. Освободите память и попробуйте снова."
            }
        }
        // Файлы на месте, а движок не поднялся — это уже не сеть и не место.
        if (isModelInstalled()) return REASON_ENGINE_NOT_UP
        // prepare() не различает «нет сети», «битый архив» и «SHA-256 не сошёлся»:
        // возвращает только Boolean. Поэтому в тексте перечислены все три причины, а не
        // выдумана одна.
        return REASON_DOWNLOAD_FAILED
    }

    /** Причина по исключению: сообщение полезно разработчику и человеку, поэтому не теряем. */
    private fun describe(error: Throwable): String {
        val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        return "Не удалось подготовить голос: $detail"
    }

    companion object {
        /**
         * Готовая связка для экрана: модель с релеем прогресса + бэкенд поверх неё.
         *
         * Порядок обязателен: `onProgress` у [OfflineTtsModel] задаётся только в
         * конструкторе, поэтому релей создаётся ДО модели и передаётся дальше в этот же
         * [OfflineTtsWarmup] — иначе колбэк [warmUp] останется без событий.
         */
        fun forApp(
            context: Context,
            diagnostics: WarmUpDiagnostics? = AndroidWarmUpDiagnostics(context),
        ): OfflineTtsWarmup {
            val relay = ProgressRelay()
            val model = OfflineTtsModel.forApp(context) { downloaded, total ->
                relay.emit(downloaded, total)
            }
            val port = OfflineTtsBackend(context, model = model)
            return OfflineTtsWarmup(port, model, diagnostics, relay)
        }

        /** Пауза после отказа: столько не пытаемся качать снова без явного «Повторить». */
        private const val RETRY_COOLDOWN_NANOS = 60L * 1_000_000_000L

        /**
         * Сколько места нужно на пике: архив 21 МиБ + распакованное дерево 36,6 МиБ + запас.
         * Меньше — распаковка упадёт на середине, оставив мусор в staging.
         */
        private const val REQUIRED_FREE_BYTES = 64L * 1024 * 1024

        private const val REASON_NO_NETWORK =
            "Нет интернета — голос не скачался. Подключитесь к сети и попробуйте снова."

        private const val REASON_DOWNLOAD_FAILED =
            "Не удалось скачать голос: архив не скачался или не прошёл проверку целостности " +
                "(SHA-256). Проверьте связь и попробуйте ещё раз."

        private const val REASON_ENGINE_NOT_UP =
            "Файлы голоса на месте, но движок не запустился. Перезапустите приложение; " +
                "если не поможет — удалите голос и скачайте заново."

        private const val REASON_RELEASED =
            "Голос уже освобождён — подготовка невозможна до повторного открытия экрана."

        private fun megabytes(bytes: Long): Long = (bytes / (1024 * 1024)).coerceAtLeast(1)
    }
}

/** Итог подготовки: успех либо причина отказа текстом для человека. */
sealed interface WarmUpResult {
    /** Причина отказа; у успеха её нет. */
    val reason: String?

    /** Движок готов, `prepare()` больше не нужен. */
    object Ready : WarmUpResult {
        override val reason: String? get() = null
    }

    /** Подготовка не удалась; [reason] можно показать как есть. */
    data class Failed(override val reason: String) : WarmUpResult
}

/**
 * Проверки окружения для человеческой причины отказа.
 *
 * Отдельный интерфейс, чтобы [OfflineTtsWarmup] не тянул `android.*` в JVM-тесты: в тестах
 * подставляется фейк, в приложении — [AndroidWarmUpDiagnostics].
 */
interface WarmUpDiagnostics {
    /** Есть ли сеть с выходом в интернет. `false` — виновата сеть. */
    fun hasNetwork(): Boolean

    /** Свободно байт в приватном хранилище приложения. `< 0` — неизвестно. */
    fun freeBytes(): Long
}

/**
 * Реальные проверки на устройстве.
 *
 * `ACCESS_NETWORK_STATE` объявлен в манифесте; если система всё же откажет, сеть считается
 * живой — обвинять её без доказательств нельзя.
 */
class AndroidWarmUpDiagnostics(private val context: Context) : WarmUpDiagnostics {

    override fun hasNetwork(): Boolean = try {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager == null) {
            // Без ConnectivityManager (не должно случаться) сеть не обвиняем.
            true
        } else {
            val network = manager.activeNetwork
            val capabilities = network?.let(manager::getNetworkCapabilities)
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
        }
    } catch (denied: SecurityException) {
        // Нет ACCESS_NETWORK_STATE — доказательств против сети нет.
        true
    }

    override fun freeBytes(): Long = try {
        StatFs(context.applicationContext.filesDir.absolutePath).availableBytes
    } catch (error: Throwable) {
        -1L
    }
}

/**
 * Канал прогресса от загрузчика модели к экрану.
 *
 * `OfflineTtsModel` получает `onProgress` один раз в конструкторе, а экран приходит со своим
 * колбэком позже — релей и есть эта развязка. Слушателей может быть несколько (второй
 * «Озвучить» ждёт тот же самое скачивание и тоже хочет проценты), поэтому это набор, а не
 * одно поле. Исключение из колбэка экрана не должно убивать скачивание: оно гасится здесь.
 */
class ProgressRelay {

    private val listeners = CopyOnWriteArraySet<(Long, Long) -> Unit>()

    /** Подписать слушателя. Возвращённая функция отписывает (идемпотентно). */
    fun attach(listener: (Long, Long) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener); Unit }
    }

    /** Разослать прогресс всем подписчикам. Зовётся с потока загрузки. */
    fun emit(downloaded: Long, total: Long) {
        for (listener in listeners) {
            // Ошибка UI-колбэка не должна обрывать скачивание 21 МиБ.
            runCatching { listener(downloaded, total) }
        }
    }
}
