package ru.mars.tv

import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Результат запроса перевода — шов [TvLyricsTranslationFlow] (контракт-образец:
 * [TvLyricsResult]): каждый исход — явное значение, исключение наружу не уходит.
 */
sealed interface TvLyricsTranslationResult {
    /** Сервис ответил `translated` (в том числе пустой строкой — пустоту судит flow, не шов). */
    data class Translated(val text: String) : TvLyricsTranslationResult

    /** Сеть/транспорт/сервис отказали; [message] — сырой текст для логов (в панель не попадает). */
    data class Failed(val message: String) : TvLyricsTranslationResult
}

/**
 * Шов запроса перевода: продакшен-реализация — [RepositoryTvMusicGateway]
 * (core-network `MusicRepository.translateLyrics(text)` -> `POST {baseUrl}/api/lyrics/translate`
 * с телом `{"text": …}`). Вызывается на фоновом [Executor] — НЕ на главном потоке.
 */
fun interface TvLyricsTranslationGateway {
    fun translate(text: String): TvLyricsTranslationResult
}

/** Причина неудачи перевода: по виду [TvMusicActivity] берёт понятную строку из ресурсов. */
enum class TvLyricsTranslationFailure {
    /** Сервис вернул пустой `translated` — не ошибка, но и перевода нет (текст остаётся оригиналом). */
    EMPTY_ANSWER,

    /** Сеть/транспорт/сервис отказали. */
    NETWORK,
}

/**
 * Состояние переключателя «Перевод»/«Оригинал» — надстройка над [TvLyricsPanelState]:
 * состояния панели (Idle/Loading/Text/Missing/Error) не трогаются, перевод меняет только
 * тело, подпись кнопки и строку метаданных.
 */
sealed interface TvLyricsTranslationState {
    /** Перевода нет: панель показывает оригинал (стартовое состояние и после [TvLyricsTranslationFlow.reset]). */
    data object Off : TvLyricsTranslationState

    /** Запрос в пути: тело ещё оригинал, кнопка — «Переводим…» (двойное нажатие глушится). */
    data object Translating : TvLyricsTranslationState

    /** Перевод получен и закэширован: [showing] — показан ли он сейчас (переключение без повторного запроса). */
    data class Ready(val text: String, val showing: Boolean) : TvLyricsTranslationState

    /** Неудача: вид [reason] выбирает понятную строку из ресурсов, тело остаётся оригиналом, кнопка — повтор. */
    data class Failed(val reason: TvLyricsTranslationFailure) : TvLyricsTranslationState
}

/**
 * Чистый конечный автомат перевода текста песни — TV-аналог mobile-переключателя
 * «Перевод»/«Оригинал» (MusicActivity.onLyricsClicked + LyricsTranslationPolicy),
 * собранный по образцу [TvLyricsPanelFlow]: gateway/executor/ui-dispatcher — чистые швы,
 * каждая ветка JVM-тестируема ([TvLyricsTranslationFlowTest]), Android — только рендер
 * в [TvMusicActivity].
 *
 * Правила:
 * - [toggle] из [TvLyricsTranslationState.Off]/[TvLyricsTranslationState.Failed] стартует
 *   запрос (сразу [TvLyricsTranslationState.Translating]); из [TvLyricsTranslationState.Ready]
 *   переключает «перевод <-> оригинал» БЕЗ сети (перевод закэширован — паритет с mobile);
 *   повторный [toggle] в [TvLyricsTranslationState.Translating] глушится — двойное нажатие
 *   не дублирует запрос (Job-guard панели);
 * - непустой ответ -> [TvLyricsTranslationState.Ready] с showing=true; пустой
 *   (`translated:""`) -> [TvLyricsTranslationState.Failed] с
 *   [TvLyricsTranslationFailure.EMPTY_ANSWER]: оригинал остаётся на экране;
 * - отказ или крах gateway -> [TvLyricsTranslationState.Failed] с
 *   [TvLyricsTranslationFailure.NETWORK]: панель не падает, сырое серверное сообщение
 *   пользователю не показывается (строку по виду берёт Activity из ресурсов);
 * - [reset] (смена трека/закрытие панели) возвращает
 *   [TvLyricsTranslationState.Off] и инвалидирует запрос в пути (generation-guard —
 *   паритет с [TvLyricsPanelFlow.close]);
 * - после [destroy] поток не публикует состояния.
 *
 * Потоковая модель: состояния доставляются через [uiDispatcher] и читаются только на
 * UI-потоке (thread-confinement — как у [TvLyricsPanelFlow]).
 */
class TvLyricsTranslationFlow(
    private val gateway: TvLyricsTranslationGateway,
    private val executor: Executor = SHARED_EXECUTOR,
    private val uiDispatcher: TvUiDispatcher = MainThreadTvDispatcher(),
) {

    /** Текущее опубликованное состояние переключателя (только UI-поток). */
    var state: TvLyricsTranslationState = TvLyricsTranslationState.Off
        private set

    /** Хук рендера: вызывается на UI-потоке при каждой смене [state]. */
    var onStateChanged: ((TvLyricsTranslationState) -> Unit)? = null

    /** Монотонный счётчик запросов; reset/destroy инвалидируют устаревшие ответы. */
    private var generation = 0

    @Volatile
    private var destroyed = false

    /**
     * Нажатие кнопки «Перевод»/«Оригинал». [original] — тело панели
     * ([TvLyricsPanelState.Text.body]); без настоящего текста переключение не стартует
     * ([TvLyricsTranslationPolicy.canTranslate]) — в UI кнопка при этом и вовсе скрыта.
     */
    fun toggle(original: String?) {
        if (destroyed) return
        when (val current = state) {
            // Переключение кэша: перевод <-> оригинал, без повторного запроса (паритет с mobile).
            is TvLyricsTranslationState.Ready -> setState(current.copy(showing = !current.showing))

            // Запрос в пути: повторное нажатие глушится (Job-guard).
            TvLyricsTranslationState.Translating -> Unit

            TvLyricsTranslationState.Off, is TvLyricsTranslationState.Failed -> {
                if (!TvLyricsTranslationPolicy.canTranslate(original)) return
                val text = original.orEmpty()
                val gen = ++generation
                setState(TvLyricsTranslationState.Translating)
                executor.execute {
                    val result = try {
                        gateway.translate(text)
                    } catch (error: Throwable) {
                        // Крах gateway не должен убивать фоновый поток переключателя.
                        TvLyricsTranslationResult.Failed(
                            error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
                        )
                    }
                    uiDispatcher.dispatch {
                        // Ответ устаревшего поколения (reset/destroy) выбрасываем.
                        if (destroyed || gen != generation) return@dispatch
                        setState(
                            when (result) {
                                is TvLyricsTranslationResult.Translated ->
                                    if (result.text.isNotBlank()) {
                                        TvLyricsTranslationState.Ready(result.text, showing = true)
                                    } else {
                                        // translated:"" — не ошибка, но и не перевод: оригинал остаётся.
                                        TvLyricsTranslationState.Failed(TvLyricsTranslationFailure.EMPTY_ANSWER)
                                    }
                                // Сырое сообщение шва в панель не идёт: вид NETWORK для строки из ресурсов.
                                is TvLyricsTranslationResult.Failed ->
                                    TvLyricsTranslationState.Failed(TvLyricsTranslationFailure.NETWORK)
                            },
                        )
                    }
                }
            }
        }
    }

    /**
     * Смена трека/закрытие панели: обратно в [TvLyricsTranslationState.Off], запрос в пути
     * инвалидируется. Из уже-[TvLyricsTranslationState.Off] ничего не публикуется (хук рендера
     * не дёргается впустую), но поколение всё равно сбрасывается.
     */
    fun reset() {
        if (destroyed) return
        generation++
        if (state != TvLyricsTranslationState.Off) setState(TvLyricsTranslationState.Off)
    }

    /** Терминальное состояние: публикация и фоновые запросы глушатся навсегда. */
    fun destroy() {
        destroyed = true
        generation++
    }

    private fun setState(newState: TvLyricsTranslationState) {
        state = newState
        onStateChanged?.invoke(newState)
    }

    companion object {
        /** Свой daemon-поток на переводы: медленный ответ не занимает очередь запросов текста. */
        private val SHARED_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tv-music-lyrics-translate").apply { isDaemon = true }
        }
    }
}

/**
 * Чистая policy переключателя «Перевод»/«Оригинал» — порт mobile LyricsTranslationPolicy
 * (app-mobile MusicLibraryPolicy.kt: отдельного файла там тоже нет — policy живёт вместе
 * с автоматом) + панельные правила: видимость кнопки по состоянию панели, подпись по
 * состоянию переключателя, заметка о неудаче. Сами строки живут в ресурсах
 * (strings_tv_lyrics.xml): policy возвращает СМЫСЛ (вид подписи/причины),
 * [TvMusicActivity] — текст (единственный источник строк, без дублей-констант).
 * Без Android-импортов — JVM-тестируема ([TvLyricsTranslationFlowTest]).
 *
 * Правило, как у mobile: пустой перевод (сервис отдаёт `translated:""`) не стирает текст —
 * [bodyFor] всегда откатывается к оригиналу.
 */
object TvLyricsTranslationPolicy {

    /** Смысл подписи кнопки: что сделает нажатие прямо сейчас. */
    enum class Label { TRANSLATE, ORIGINAL, TRANSLATING }

    /** Подпись кнопки по текущему состоянию переключателя (в запросе — «Переводим…», паритет с mobile). */
    fun buttonLabel(state: TvLyricsTranslationState): Label = when (state) {
        TvLyricsTranslationState.Translating -> Label.TRANSLATING
        is TvLyricsTranslationState.Ready -> actionLabel(state.showing)
        TvLyricsTranslationState.Off, is TvLyricsTranslationState.Failed -> Label.TRANSLATE
    }

    /** Кнопка называет действие: «Оригинал» — пока показан перевод, иначе «Перевод» (паритет с mobile). */
    fun actionLabel(showingTranslation: Boolean): Label =
        if (showingTranslation) Label.ORIGINAL else Label.TRANSLATE

    /** Переводить можно, только когда есть настоящий непустой оригинал (паритет с mobile). */
    fun canTranslate(original: String?): Boolean = !original.isNullOrBlank()

    /**
     * Что показать в теле панели: перевод, когда он есть, непустой и включён; иначе оригинал.
     * Пустой/пробельный перевод никогда не стирает текст.
     */
    fun bodyFor(original: String, translation: String?, showingTranslation: Boolean): String {
        val translated = translation?.takeIf { it.isNotBlank() } ?: return original
        return if (showingTranslation) translated else original
    }

    /** Тело панели по состоянию переключателя: надстройка [bodyFor] над оригиналом. */
    fun bodyFor(original: String, state: TvLyricsTranslationState): String = when (state) {
        is TvLyricsTranslationState.Ready -> bodyFor(original, state.text, state.showing)
        TvLyricsTranslationState.Off,
        TvLyricsTranslationState.Translating,
        is TvLyricsTranslationState.Failed,
        -> original
    }

    /**
     * Кнопка живёт, только когда есть что переводить: панель показала настоящий текст
     * (заглушка Missing, ошибка и загрузка — без переключателя).
     */
    fun buttonVisible(panel: TvLyricsPanelState): Boolean =
        panel is TvLyricsPanelState.Text && canTranslate(panel.body)

    /** Вид неудачи перевода (ключ понятной строки из ресурсов); null — заметка не нужна. */
    fun failureOf(state: TvLyricsTranslationState): TvLyricsTranslationFailure? =
        (state as? TvLyricsTranslationState.Failed)?.reason

    /**
     * Строка метаданных под заголовком: мета панели («источник: youtube», [TvLyricsViewPolicy.metaLine])
     * и заметка перевода (понятная строка неудачи), через разделитель; обе пустые — строку прячем.
     */
    fun metaLine(panelMeta: String, note: String): String = buildList {
        panelMeta.takeIf { it.isNotBlank() }?.let { add(it) }
        note.takeIf { it.isNotBlank() }?.let { add(it) }
    }.joinToString(" · ")
}
