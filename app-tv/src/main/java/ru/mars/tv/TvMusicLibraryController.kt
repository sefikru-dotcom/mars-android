package ru.mars.tv

import ru.mars.network.model.HistoryEntry
import ru.mars.network.model.MusicTrack
import ru.mars.network.model.Playlist
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Шов библиотеки TV-музыки: серверные плейлисты, треки плейлиста и история прослушивания
 * (mars-music: `GET /api/playlists`, `GET /api/playlist/{id}`, `GET /api/history`,
 * `POST /api/history/record`). Реализация — [RepositoryTvMusicGateway] поверх настоящего
 * core-network `MusicRepository`; тесты подставляют фейк. Ни один метод не бросает:
 * сбой приезжает вариантом `Failed`, поэтому крах бэкенда не может уронить экран.
 */
interface TvMusicLibraryGateway {

    /** Все плейлисты аккаунта (`GET /api/playlists`). Пустой список — валидный ответ. */
    fun fetchPlaylists(): TvPlaylistsResult

    /** Треки одного плейлиста (`GET /api/playlist/{id}`). */
    fun fetchPlaylistTracks(playlistId: String): TvPlaylistTracksResult

    /** История прослушивания (`GET /api/history`), новые сверху. */
    fun fetchHistory(): TvHistoryResult

    /**
     * Запись прослушивания (`POST /api/history/record`) — паритет с mobile: трек,
     * запущенный на приставке, появляется в истории аккаунта. Сбой не фатален: возвращает
     * `false`, воспроизведение от этого не страдает.
     */
    fun recordHistory(track: MusicTrack): Boolean
}

/** Ответ по списку плейлистов. */
sealed interface TvPlaylistsResult {
    data class Loaded(val playlists: List<Playlist>) : TvPlaylistsResult
    data class Failed(val message: String) : TvPlaylistsResult
}

/** Ответ по трекам открытого плейлиста. */
sealed interface TvPlaylistTracksResult {
    data class Loaded(val tracks: List<MusicTrack>) : TvPlaylistTracksResult
    data class Failed(val message: String) : TvPlaylistTracksResult
}

/** Ответ по истории прослушивания. */
sealed interface TvHistoryResult {
    data class Loaded(val entries: List<HistoryEntry>) : TvHistoryResult
    data class Failed(val message: String) : TvHistoryResult
}

/** Состояния вкладки «Плейлисты»: ровно одно активно в каждый момент. */
sealed interface TvPlaylistsState {
    /** Ничего ещё не запрашивали (вкладку открыли впервые). */
    object Idle : TvPlaylistsState

    /** Запрос в пути на фоновом исполнителе. */
    object Loading : TvPlaylistsState

    /** Сбой сети/сервера; [message] — понятная причина и повод для «Повторить». */
    data class Failed(val message: String) : TvPlaylistsState

    /** Сервер ответил, плейлистов нет — это состояние, а не ошибка. */
    object Empty : TvPlaylistsState

    /** Список плейлистов в серверном порядке (новые сверху, как отдаёт сервис). */
    data class Content(val playlists: List<Playlist>) : TvPlaylistsState
}

/**
 * Состояния drill-down «плейлист открыт»: заголовок плейлиста известен во всех вариантах,
 * кроме [Idle], поэтому шапка вкладки не мигает при переходах.
 */
sealed interface TvPlaylistTracksState {
    /** Плейлист не открыт — вкладка показывает список плейлистов. */
    object Idle : TvPlaylistTracksState

    /** Треки запрошены: шапка уже знает, какой плейлист открыт. */
    data class Loading(val playlist: Playlist) : TvPlaylistTracksState

    /** Запрос упал; [playlist] остаётся, чтобы «Повторить» повторил именно его. */
    data class Failed(val playlist: Playlist, val message: String) : TvPlaylistTracksState

    /** Плейлист пуст — строк нет, но из него можно вернуться к списку. */
    data class Empty(val playlist: Playlist) : TvPlaylistTracksState

    /** Треки плейлиста: строки экрана и очередь воспроизведения — один и тот же список. */
    data class Content(val playlist: Playlist, val tracks: List<MusicTrack>) : TvPlaylistTracksState
}

/** Состояния вкладки «История». */
sealed interface TvHistoryState {
    object Idle : TvHistoryState
    object Loading : TvHistoryState
    data class Failed(val message: String) : TvHistoryState
    object Empty : TvHistoryState
    data class Content(val entries: List<HistoryEntry>) : TvHistoryState
}

/**
 * Контроллер библиотеки TV-музыки — «Плейлисты» и «История» (паритет мобильных вкладок
 * `MusicActivity`, JVM-тестируемый: [TvMusicLibraryGateway] и [TvMusicTransport] — швы,
 * Android остаётся тонким рендером в [TvMusicActivity]).
 *
 * Правила:
 * - каждая загрузка публикует `Loading` и уходит на фоновый [executor]; результат
 *   возвращается на UI-поток через [uiDispatcher] и превращается в состояние чистой
 *   [TvMusicLibraryPolicy] (пустой ответ — `Empty`, сбой — `Failed` с непустым текстом);
 * - у каждой ветки СВОЁ поколение запросов: поздний ответ списка не перекрывает свежий
 *   drill-down, а ответ закрытого плейлиста не всплывает поверх списка;
 * - повторное нажатие на уже загружающееся не запускает второй запрос (guard от двойного
 *   тапа пультом), а «Повторить» повторяет именно ту операцию, что упала;
 * - выбор строки отдаёт ВЕСЬ показанный список транспорту как очередь
 *   ([TvMusicTransport.playPlaylist]) — паритет с mobile: следующий трек играет сам;
 * - запуск трека пишется в серверную историю один раз на трек ([onTrackStarted]):
 *   транспорт присылает кадры и на паузу/возобновление, дубли в историю не нужны;
 * - после [destroy] состояния не публикуются, а запросы в пути не имеют эффекта.
 */
class TvMusicLibraryController(
    private val gateway: TvMusicLibraryGateway,
    private val transport: TvMusicTransport,
    private val executor: Executor = SHARED_EXECUTOR,
    private val uiDispatcher: TvUiDispatcher = MainThreadTvDispatcher(),
) {

    /** Текущее состояние вкладки «Плейлисты» (только UI-поток). */
    var playlistsState: TvPlaylistsState = TvPlaylistsState.Idle
        private set

    /** Текущее состояние drill-down (только UI-поток). */
    var playlistTracksState: TvPlaylistTracksState = TvPlaylistTracksState.Idle
        private set

    /** Текущее состояние вкладки «История» (только UI-поток). */
    var historyState: TvHistoryState = TvHistoryState.Idle
        private set

    /** Хуки рендера: вызываются на UI-потоке при каждой смене соответствующего состояния. */
    var onPlaylistsChanged: ((TvPlaylistsState) -> Unit)? = null
    var onPlaylistTracksChanged: ((TvPlaylistTracksState) -> Unit)? = null
    var onHistoryChanged: ((TvHistoryState) -> Unit)? = null

    /** Открытый плейлист (null — вкладка показывает список). */
    val openPlaylist: Playlist?
        get() = when (val current = playlistTracksState) {
            is TvPlaylistTracksState.Loading -> current.playlist
            is TvPlaylistTracksState.Failed -> current.playlist
            is TvPlaylistTracksState.Empty -> current.playlist
            is TvPlaylistTracksState.Content -> current.playlist
            TvPlaylistTracksState.Idle -> null
        }

    private var playlistsGeneration = 0
    private var tracksGeneration = 0
    private var historyGeneration = 0

    /** Последний записанный в историю трек: транспорт шлёт кадры чаще, чем меняется трек. */
    private var recordedTrackId: String? = null

    @Volatile
    private var destroyed = false

    /** Загрузка списка плейлистов; повторный вызов во время загрузки игнорируется. */
    fun loadPlaylists() {
        if (destroyed || playlistsState is TvPlaylistsState.Loading) return
        val generation = ++playlistsGeneration
        setPlaylists(TvPlaylistsState.Loading)
        executor.execute {
            val result = safelyPlaylists { gateway.fetchPlaylists() }
            uiDispatcher.dispatch {
                if (destroyed || generation != playlistsGeneration) return@dispatch
                setPlaylists(TvMusicLibraryPolicy.playlistsState(result))
            }
        }
    }

    /** Загрузка истории; повторный вызов во время загрузки игнорируется. */
    fun loadHistory() {
        if (destroyed || historyState is TvHistoryState.Loading) return
        val generation = ++historyGeneration
        setHistory(TvHistoryState.Loading)
        executor.execute {
            val result = safelyHistory { gateway.fetchHistory() }
            uiDispatcher.dispatch {
                if (destroyed || generation != historyGeneration) return@dispatch
                setHistory(TvMusicLibraryPolicy.historyState(result))
            }
        }
    }

    /** Открывает плейлист: Loading со сразу известной шапкой, затем треки/пусто/сбой. */
    fun openPlaylist(playlist: Playlist) {
        if (destroyed) return
        val current = playlistTracksState
        if (current is TvPlaylistTracksState.Loading && current.playlist.id == playlist.id) return
        val generation = ++tracksGeneration
        setPlaylistTracks(TvPlaylistTracksState.Loading(playlist))
        executor.execute {
            val result = safelyPlaylistTracks { gateway.fetchPlaylistTracks(playlist.id) }
            uiDispatcher.dispatch {
                if (destroyed || generation != tracksGeneration) return@dispatch
                setPlaylistTracks(TvMusicLibraryPolicy.playlistTracksState(playlist, result))
            }
        }
    }

    /** Возврат из drill-down к списку плейлистов без повторного запроса. */
    fun closePlaylist() {
        if (destroyed) return
        tracksGeneration++
        setPlaylistTracks(TvPlaylistTracksState.Idle)
    }

    /**
     * «Повторить» на вкладке «Плейлисты»: открытый плейлист повторяет свои треки, иначе —
     * список плейлистов. Ничего не загружено — ведёт себя как первый вход на вкладку.
     */
    fun retryPlaylists() {
        if (destroyed) return
        val playlist = openPlaylist
        if (playlist != null) {
            playlistTracksState = TvPlaylistTracksState.Idle // снимаем guard «уже грузится»
            openPlaylist(playlist)
        } else {
            playlistsState = TvPlaylistsState.Idle
            loadPlaylists()
        }
    }

    /** «Повторить» на вкладке «История». */
    fun retryHistory() {
        if (destroyed) return
        historyState = TvHistoryState.Idle
        loadHistory()
    }

    /**
     * Выбор трека в открытом плейлисте: очередь — весь плейлист, старт с [index].
     * Индекс вне списка (устаревший кадр) — no-op, а не падение.
     */
    fun onPlaylistTrackActivated(index: Int) {
        val content = playlistTracksState as? TvPlaylistTracksState.Content ?: return
        if (index !in content.tracks.indices) return
        transport.playPlaylist(content.tracks, index)
    }

    /**
     * Выбор трека в истории: очередь — вся история в серверном порядке (новые сверху),
     * старт с [index] — паритет с mobile, где история играется как список.
     */
    fun onHistoryTrackActivated(index: Int) {
        val content = historyState as? TvHistoryState.Content ?: return
        val queue = TvMusicLibraryPolicy.historyQueue(content.entries)
        if (index !in queue.indices) return
        transport.playPlaylist(queue, index)
    }

    /**
     * Транспорт начал играть [track]: один запрос `POST /api/history/record` на трек.
     * Повторные кадры того же трека (пауза/возобновление/прогресс) не пишутся — иначе
     * история заполнялась бы десятками копий одной песни.
     */
    fun onTrackStarted(track: MusicTrack?) {
        if (destroyed) return
        val id = track?.id?.takeIf { it.isNotBlank() } ?: return
        if (id == recordedTrackId) return
        recordedTrackId = id
        executor.execute {
            // Сбой записи истории не влияет на воспроизведение: результат не нужен экрану.
            runCatching { gateway.recordHistory(track) }
        }
    }

    /** Терминальное состояние: публикация и фоновые запросы глушатся навсегда. */
    fun destroy() {
        destroyed = true
        playlistsGeneration++
        tracksGeneration++
        historyGeneration++
    }

    private fun setPlaylists(state: TvPlaylistsState) {
        playlistsState = state
        onPlaylistsChanged?.invoke(state)
    }

    private fun setPlaylistTracks(state: TvPlaylistTracksState) {
        playlistTracksState = state
        onPlaylistTracksChanged?.invoke(state)
    }

    private fun setHistory(state: TvHistoryState) {
        historyState = state
        onHistoryChanged?.invoke(state)
    }

    /**
     * Крах шва не должен убивать фоновый поток экрана: каждая ветка превращает его в свой
     * `Failed` с понятным текстом.
     */
    private fun safelyPlaylists(block: () -> TvPlaylistsResult): TvPlaylistsResult = try {
        block()
    } catch (error: Throwable) {
        TvPlaylistsResult.Failed(reasonOf(error))
    }

    private fun safelyPlaylistTracks(block: () -> TvPlaylistTracksResult): TvPlaylistTracksResult = try {
        block()
    } catch (error: Throwable) {
        TvPlaylistTracksResult.Failed(reasonOf(error))
    }

    private fun safelyHistory(block: () -> TvHistoryResult): TvHistoryResult = try {
        block()
    } catch (error: Throwable) {
        TvHistoryResult.Failed(reasonOf(error))
    }

    /** Причина сбоя для показа: сообщение исключения, иначе имя класса. */
    private fun reasonOf(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    companion object {
        /** Один daemon-поток на библиотеку (не конкурирует с чартами, лентой и текстами). */
        private val SHARED_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tv-music-library").apply { isDaemon = true }
        }
    }
}
