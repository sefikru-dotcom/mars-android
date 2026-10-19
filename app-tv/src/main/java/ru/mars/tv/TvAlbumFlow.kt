package ru.mars.tv

import ru.mars.network.repository.MusicAlbum
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Результат запроса альбома — транспорт-независимый шов диалога
 * ([TvLyricsResult] и [TvMusicChartsResult] — образцы контракта).
 */
sealed interface TvAlbumResult {
    /** Альбом найден и разобран вместе с треками. */
    data class Loaded(val album: MusicAlbum) : TvAlbumResult

    /** Сервер не знает такого альбома (`AlbumResult.InvalidBrowseId`) — повтор не поможет. */
    data object NotFound : TvAlbumResult

    /** Сеть/сервер; [message] — техническая деталь, текст для UI даёт [TvAlbumPolicy]. */
    data class Failed(val message: String) : TvAlbumResult
}

/**
 * Шов запроса альбома: продакшен-реализация — [RepositoryTvMusicGateway]
 * (core-network `MusicRepository.getAlbum(browseId)` — тот же метод, что у мобильного
 * диалога альбома; новый клиент не пишем). Вызывается на фоновом [Executor].
 */
fun interface TvAlbumGateway {
    fun fetchAlbum(browseId: String): TvAlbumResult
}

/** Чистое UI-состояние диалога альбома (Activity только рендерит его). */
sealed interface TvAlbumPanelState {
    /** Диалог закрыт (или ещё не открывался). */
    data object Idle : TvAlbumPanelState

    /** Диалог открыт, запрос в пути. */
    data class Loading(val browseId: String) : TvAlbumPanelState

    /** Альбом получен; [album] уже несёт список треков. */
    data class Loaded(val album: MusicAlbum) : TvAlbumPanelState

    /** Сервер такого альбома не знает — кнопки «Повторить» нет. */
    data class NotFound(val browseId: String) : TvAlbumPanelState

    /** Сеть/сервер упали; [message] показывается как есть (не пустой). */
    data class Error(val browseId: String, val message: String) : TvAlbumPanelState
}

/**
 * Конечный автомат диалога альбома на TV — JVM-тестируемый аналог мобильного
 * `openAlbumDialog()` (lifecycleScope + Job-guard), где gateway/executor/ui-dispatcher
 * — чистые швы, а Android остаётся только тонким рендером в [TvMusicActivity].
 *
 * Правила (mobile parity):
 * - [open] сразу переводит диалог в [TvAlbumPanelState.Loading] и стартует фоновый
 *   запрос; browseId без префикса `MPRE` не уходит в сеть вовсе — сразу [NotFound]
 *   ([TvAlbumPolicy.requestTarget]), иначе сервер ответил бы кодом 500;
 * - повторный [open] того же альбома, пока запрос в пути, игнорируется (Job-guard);
 * - [close] и новый [open] инвалидируют поколение: ответ устаревшего запроса не
 *   перекрывает свежее состояние;
 * - крах gateway не выбрасывается наружу — превращается в [TvAlbumPanelState.Error];
 * - после [destroy] поток не публикует состояния.
 */
class TvAlbumFlow(
    private val gateway: TvAlbumGateway,
    private val executor: Executor = SHARED_EXECUTOR,
    private val uiDispatcher: TvUiDispatcher = MainThreadTvDispatcher(),
) {

    /** Текущее опубликованное состояние (только UI-поток). */
    var state: TvAlbumPanelState = TvAlbumPanelState.Idle
        private set

    /** Хук рендера: вызывается на UI-потоке при каждой смене [state]. */
    var onStateChanged: ((TvAlbumPanelState) -> Unit)? = null

    /** Монотонный счётчик запросов; close/open инвалидируют устаревшие ответы. */
    private var generation = 0

    @Volatile
    private var destroyed = false

    /**
     * Открывает диалог для альбома [rawBrowseId]. Невалидный id — сразу [NotFound],
     * без запроса; валидный — Loading и фоновый запрос.
     */
    fun open(rawBrowseId: String?) {
        if (destroyed) return
        val browseId = TvAlbumPolicy.requestTarget(rawBrowseId)
        if (browseId == null) {
            generation++
            setState(TvAlbumPanelState.NotFound(rawBrowseId?.trim().orEmpty()))
            return
        }
        val current = state
        if (current is TvAlbumPanelState.Loading && current.browseId == browseId) return
        val gen = ++generation
        setState(TvAlbumPanelState.Loading(browseId))
        executor.execute {
            val result = try {
                gateway.fetchAlbum(browseId)
            } catch (error: Throwable) {
                // Крах gateway не должен убивать фоновый поток диалога.
                TvAlbumResult.Failed(error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)
            }
            uiDispatcher.dispatch {
                if (destroyed || gen != generation) return@dispatch
                setState(
                    when (result) {
                        is TvAlbumResult.Loaded -> TvAlbumPanelState.Loaded(result.album)
                        TvAlbumResult.NotFound -> TvAlbumPanelState.NotFound(browseId)
                        is TvAlbumResult.Failed -> TvAlbumPanelState.Error(
                            browseId,
                            result.message.ifBlank { TvAlbumPolicy.LOAD_FAILURE },
                        )
                    },
                )
            }
        }
    }

    /** Повтор последнего запроса (кнопка «Повторить»): без текущего id — no-op. */
    fun retry() {
        if (destroyed) return
        val browseId = when (val current = state) {
            is TvAlbumPanelState.Error -> current.browseId
            is TvAlbumPanelState.NotFound -> return // повтор заведомо тот же отказ
            else -> return
        }
        state = TvAlbumPanelState.Idle // снимаем guard «уже грузится»
        open(browseId)
    }

    /** Диалог закрыт: инвалидирует запрос в пути и возвращает [TvAlbumPanelState.Idle]. */
    fun close() {
        if (destroyed) return
        generation++
        setState(TvAlbumPanelState.Idle)
    }

    /** Терминальное состояние: публикация и фоновые запросы глушатся навсегда. */
    fun destroy() {
        destroyed = true
        generation++
    }

    private fun setState(newState: TvAlbumPanelState) {
        state = newState
        onStateChanged?.invoke(newState)
    }

    companion object {
        /** Один daemon-поток на альбомы (не конкурирует с чартами и текстами). */
        private val SHARED_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tv-music-album").apply { isDaemon = true }
        }
    }
}
