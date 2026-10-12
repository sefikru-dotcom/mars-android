package ru.mars.mobile

import kotlinx.coroutines.CancellationException
import ru.mars.network.model.ServerLogFile
import ru.mars.network.model.ServerLogTail
import ru.mars.network.model.ServerLogWindow
import ru.mars.network.repository.ServerLogsResult

/**
 * Презентер вкладки Logs экрана мониторинга: оркестрирует загрузку хвоста
 * whitelisted лог-файла (порт [fetch] — в app это
 * `LogsRepository().getLogs(baseUrl, credential, file, tail)`) и offline-safe
 * состояния. Чистый JVM-класс без Android-импортов — переходы покрыты тестами.
 *
 * Контракт состояний:
 * - нет credential -> [LogsPhase.NO_AUTH];
 * - [ServerLogsResult.Window] с непустыми строками -> READY;
 * - Window без строк -> EMPTY (файл пуст или ещё не создан; серверный `hint`
 *   важнее общего текста — [LogsPolicy.emptyMessage]);
 * - InvalidRequest/Failed -> ERROR; прошлое окно остаётся на экране
 *   (offline-safe), ошибка уходит в notice;
 * - смена файла/tail ([selectFile]/[selectTail]) принимает только серверные
 *   enum'ы — произвольный путь лога через этот экран непредставим.
 *
 * Конкурентные загрузки (быстрые переключения файла/tail): каждый [load]
 * увеличивает [loadGeneration] и публикует результат, только если поколение
 * осталось последним — устаревший ответ более раннего запроса отбрасывается
 * (stale-race guard). Отмена корутины экрана ([CancellationException])
 * пробрасывается дальше, а не превращается в ERROR: состояние не мутируется.
 *
 * Экран read-only: только GET хвоста, никаких мутаций сервера.
 */
class LogsPresenter(
    private val hasCredential: () -> Boolean,
    private val fetch: suspend (ServerLogFile, ServerLogTail) -> ServerLogsResult,
    private val isOffline: () -> Boolean = { false },
) {

    var state: LogsUiState = LogsUiState(
        phase = LogsPhase.LOADING,
        message = LogsPolicy.MSG_LOADING,
    )
        private set

    private var onChanged: (LogsUiState) -> Unit = {}

    /** Поколение загрузки: результат устаревшего конкурентного [load] отбрасывается. */
    private var loadGeneration = 0

    /** Подписка на состояния; сразу реплеит текущее. Одна подписка на экран. */
    fun subscribe(listener: (LogsUiState) -> Unit) {
        onChanged = listener
        listener(state)
    }

    /** Загрузка/перезагрузка окна за текущие file/tail. Идемпотентна по состоянию. */
    suspend fun load() {
        if (!hasCredential()) {
            publish(
                LogsUiState(
                    phase = LogsPhase.NO_AUTH,
                    file = state.file,
                    tail = state.tail,
                    message = LogsPolicy.MSG_NO_AUTH,
                )
            )
            return
        }
        val generation = ++loadGeneration
        publish(
            state.copy(
                phase = LogsPhase.LOADING,
                notice = null,
                message = if (state.lines.isEmpty()) LogsPolicy.MSG_LOADING else null,
                canRetry = false,
            )
        )
        val result = try {
            fetch(state.file, state.tail)
        } catch (cancellation: CancellationException) {
            throw cancellation // отмена экрана — не «ошибка сети»; состояние не трогаем
        } catch (error: Exception) {
            ServerLogsResult.Failed(error.message ?: "Network error")
        }
        if (generation != loadGeneration) return // в полёте более новая загрузка — эта устарела
        when (result) {
            is ServerLogsResult.Window -> publish(ready(result.window))
            is ServerLogsResult.InvalidRequest ->
                publish(failure(LogsPolicy.msgInvalidRequest(result.message), offlineText = false))
            is ServerLogsResult.Failed ->
                publish(failure(LogsPolicy.MSG_ERROR, offlineText = true))
        }
    }

    /** Смена whitelisted файла и перезагрузка окна. */
    suspend fun selectFile(file: ServerLogFile) {
        if (file != state.file) publish(state.copy(file = file))
        load()
    }

    /** Смена whitelisted размера хвоста и перезагрузка окна. */
    suspend fun selectTail(tail: ServerLogTail) {
        if (tail != state.tail) publish(state.copy(tail = tail))
        load()
    }

    private fun ready(window: ServerLogWindow): LogsUiState {
        if (window.lines.isEmpty()) {
            // Валидный 200 «файла ещё нет»/«файл пуст» — settled-состояние, не авария.
            return LogsUiState(
                phase = LogsPhase.EMPTY,
                file = state.file,
                tail = state.tail,
                message = LogsPolicy.emptyMessage(window),
            )
        }
        return LogsUiState(
            phase = LogsPhase.READY,
            file = state.file,
            tail = state.tail,
            lines = window.lines,
            meta = LogsPolicy.meta(window),
            notice = if (window.truncated) LogsPolicy.NOTICE_TRUNCATED else null,
        )
    }

    private fun failure(message: String, offlineText: Boolean): LogsUiState {
        val offline = isOffline()
        val stale = state.lines.isNotEmpty()
        return LogsUiState(
            phase = LogsPhase.ERROR,
            file = state.file,
            tail = state.tail,
            lines = state.lines,
            meta = state.meta,
            notice = when {
                stale && offline -> LogsPolicy.NOTICE_OFFLINE_STALE
                offline -> LogsPolicy.NOTICE_OFFLINE
                else -> null
            },
            message = when {
                stale -> null
                offline && offlineText -> LogsPolicy.MSG_ERROR_OFFLINE
                else -> message
            },
            canRetry = true,
        )
    }

    private fun publish(next: LogsUiState) {
        state = next
        onChanged(next)
    }
}
