package ru.mars.tv

import ru.mars.network.model.LyricsResult
import ru.mars.network.model.MusicTrack
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Результат запроса текста песни — транспорт-независимый шов панели
 * ([TvMusicScreenController] и его [TvMusicChartsResult] — образец контракта).
 */
sealed interface TvLyricsResult {
    data class Loaded(val lyrics: LyricsResult?) : TvLyricsResult
    data class Failed(val message: String) : TvLyricsResult
}

/**
 * Шов запроса текста: продакшен-реализация — [RepositoryTvMusicGateway]
 * (core-network `MusicRepository.getLyrics(videoId, title, artist)`;
 * title/artist ОБЯЗАТЕЛЬНЫ — без них сервер часто отдаёт заглушку «Текст не найден.»).
 * Вызывается на фоновом [Executor] — НЕ на главном потоке.
 */
fun interface TvLyricsGateway {
    fun fetchLyrics(track: MusicTrack): TvLyricsResult
}

/** Чистое UI-состояние панели «Текст песни» (Activity только рендерит его). */
sealed interface TvLyricsPanelState {
    /** Панель закрыта (или ещё не открывалась). */
    data object Idle : TvLyricsPanelState

    /** Панель открыта, запрос в пути — тело показывает загрузку. */
    data class Loading(val track: MusicTrack) : TvLyricsPanelState

    /** Готовый текст; [synced] — из LRC с вырезанными таймкодами. */
    data class Text(
        val track: MusicTrack,
        val body: String,
        val synced: Boolean,
        val source: String?,
    ) : TvLyricsPanelState

    /** Сервер не дал текста: пусто во всех полях или заглушка «Текст не найден.». */
    data class Missing(val track: MusicTrack) : TvLyricsPanelState

    /** Сеть/транспорт упали; [message] показывается как есть (не пустой). */
    data class Error(val track: MusicTrack, val message: String) : TvLyricsPanelState
}

/**
 * Чистый конечный автомат панели «Текст песни» — TV-аналог mobile `onLyricsClicked()`
 * (lifecycleScope + Job-guard), собранный так, чтобы каждая ветка была JVM-тестируемой
 * ([TvLyricsPanelFlowTest]): gateway/executor/ui-dispatcher — чистые швы, Android —
 * только тонкий рендер в [TvMusicActivity].
 *
 * Правила:
 * - [open] немедленно переводит панель в [TvLyricsPanelState.Loading] и стартует фоновый
 *   запрос; ответ проходит через чистый [TvLyricsViewPolicy.resolve] (обычный текст
 *   побеждает, synced-LRC теряет таймкоды, заглушка/пустота -> Missing);
 * - защита от двойного тапа (mobile Job-guard): повторный [open] того же трека,
 *   пока запрос в пути, игнорируется — второй запрос не стартует;
 * - [close] (диалог закрыт) и новый [open] инвалидируют поколение: ответ устаревшего
 *   запроса не перекрывает свежее состояние (parity с generation-guard контроллера экрана);
 * - крах gateway не выбрасывается наружу — превращается в [TvLyricsPanelState.Error]
 *   с message-or-class-name;
 * - после [destroy] поток не публикует состояния.
 *
 * Потоковая модель: все публикуемые состояния доставляются через [uiDispatcher],
 * поэтому [state] читается только на UI-потоке (thread-confinement, как у
 * [TvMusicScreenController]).
 */
class TvLyricsPanelFlow(
    private val gateway: TvLyricsGateway,
    private val executor: Executor = SHARED_EXECUTOR,
    private val uiDispatcher: TvUiDispatcher = MainThreadTvDispatcher(),
) {

    /** Текущее опубликованное состояние панели (только UI-поток). */
    var state: TvLyricsPanelState = TvLyricsPanelState.Idle
        private set

    /** Хук рендера: вызывается на UI-потоке при каждой смене [state]. */
    var onStateChanged: ((TvLyricsPanelState) -> Unit)? = null

    /** Монотонный счётчик запросов; close/open инвалидируют устаревшие ответы. */
    private var generation = 0

    @Volatile
    private var destroyed = false

    /**
     * Открывает панель для [track]: сразу Loading, затем фоновый запрос текста.
     * Двойной тап по кнопке, пока запрос того же трека в пути, игнорируется.
     */
    fun open(track: MusicTrack) {
        if (destroyed) return
        val current = state
        // Job-guard (mobile parity): активный запрос этого трека не дублируем.
        if (current is TvLyricsPanelState.Loading && current.track.id == track.id) return
        val gen = ++generation
        setState(TvLyricsPanelState.Loading(track))
        executor.execute {
            val result = try {
                gateway.fetchLyrics(track)
            } catch (error: Throwable) {
                // Крах gateway не должен убивать фоновый поток панели.
                TvLyricsResult.Failed(error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)
            }
            uiDispatcher.dispatch {
                // Ответ устаревшего поколения (панель закрыли или открыли другой трек) выбрасываем.
                if (destroyed || gen != generation) return@dispatch
                setState(
                    when (result) {
                        is TvLyricsResult.Loaded -> when (val ui = TvLyricsViewPolicy.resolve(result.lyrics)) {
                            is TvLyricsUiState.Text -> TvLyricsPanelState.Text(track, ui.body, ui.synced, ui.source)
                            TvLyricsUiState.Missing -> TvLyricsPanelState.Missing(track)
                        }
                        is TvLyricsResult.Failed -> TvLyricsPanelState.Error(
                            track,
                            result.message.ifBlank { FETCH_FAILURE },
                        )
                    },
                )
            }
        }
    }

    /** Панель закрыта: инвалидирует запрос в пути и возвращает [TvLyricsPanelState.Idle]. */
    fun close() {
        if (destroyed) return
        generation++
        setState(TvLyricsPanelState.Idle)
    }

    /** Терминальное состояние: публикация и фоновые запросы глушатся навсегда. */
    fun destroy() {
        destroyed = true
        generation++
    }

    private fun setState(newState: TvLyricsPanelState) {
        state = newState
        onStateChanged?.invoke(newState)
    }

    companion object {
        /** Message, когда gateway вернул Failed с пустым message (state никогда не несёт пустое). */
        const val FETCH_FAILURE = "Ошибка: не удалось загрузить текст"

        /** Один daemon-поток на запросы текстов (изолирован от потока чартов экрана). */
        private val SHARED_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tv-music-lyrics").apply { isDaemon = true }
        }
    }
}
