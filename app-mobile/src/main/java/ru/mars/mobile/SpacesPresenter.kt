package ru.mars.mobile

import kotlinx.coroutines.CancellationException
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.WorkspaceItem
import ru.mars.network.model.WorkspacesResponse
import ru.mars.network.repository.WorkspaceRepository

/**
 * Результат transport-слоя: каталог пространств, отсутствие пары или сбой.
 *
 * «Нет пары» отделено от «сбой» намеренно: без device-credential запрос не
 * имеет смысла и повторять его бессмысленно, а сети/сервера может не быть
 * временно — пользователю нужны разные подсказки.
 */
sealed interface SpacesFetchResult {
    data class Success(val response: WorkspacesResponse) : SpacesFetchResult

    /** Устройство не сопряжено с сервером: каталог недоступен до прохождения пары. */
    data object NotPaired : SpacesFetchResult

    /** Транспорт/HTTP/разбор тела отказали; сырых исключений наверх не отдаём. */
    data object Failure : SpacesFetchResult
}

/**
 * Шов данных между [SpacesPresenter] и core-network (паттерн data-seam TV-экрана
 * пространств — TvSpacesGateway): на устройстве — [RepositorySpacesGateway],
 * unit-тесты подставляют fake без HTTP.
 */
interface SpacesGateway {
    suspend fun fetchSpaces(): SpacesFetchResult
}

/**
 * Продакшн-реализация: `GET /api/workspaces` через [WorkspaceRepository].
 *
 * Маршрут входит в allowlist нативного device-токена (hermes-webui
 * `api/auth.py`): чтение каталога разрешено, мутации (`/api/workspaces/add` и
 * пр.) — нет. Поэтому экран только читает каталог, а «выбор» живёт целиком в
 * клиенте ([WorkspaceSelection]) и уходит в сообщение сентинелом
 * `[Workspace::v1: …]`, не меняя состояние сервера.
 *
 * Токен читается провайдером в момент запроса и не покидает этот класс.
 */
class RepositorySpacesGateway(
    private val repository: WorkspaceRepository,
    private val baseUrl: String,
    private val credentialProvider: () -> ConsumedDeviceCredential?,
) : SpacesGateway {

    override suspend fun fetchSpaces(): SpacesFetchResult {
        val credential = credentialProvider() ?: return SpacesFetchResult.NotPaired
        return try {
            // Репозиторий fail-soft: отказ приходит как null, а не исключением.
            val response = repository.getWorkspaces(baseUrl, credential)
            if (response != null) SpacesFetchResult.Success(response) else SpacesFetchResult.Failure
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            SpacesFetchResult.Failure
        }
    }
}

/**
 * Презентер экрана «Пространства»: оркестрирует загрузку каталога через
 * [SpacesGateway], разрешение выбора через [WorkspaceSelection] и offline-safe
 * состояния. Чистый JVM-класс без Android-импортов — хранилище, сеть, детектор
 * офлайна и часы инжектятся, поэтому все переходы покрыты unit-тестами.
 *
 * Контракт состояний:
 * - нет пары -> [SpacesPhase.NO_AUTH] (загрузка даже не начинается);
 * - обновление поверх готового каталога не мигает списком: тот же каталог с
 *   `refreshing = true` (шапка показывает прогресс, строки остаются);
 * - ответ не пришёл -> каталог текущей сессии, если он моложе
 *   [SpacesPolicy.CACHE_MAX_AGE_MS] (показывается с плашкой `stale`), иначе
 *   [SpacesPhase.ERROR]; при явном сохранённом выборе экран не пустует —
 *   показывается offline-safe строка этого выбора;
 * - пустой каталог -> [SpacesPhase.EMPTY] (валидный ответ, не ошибка);
 * - успех -> [SpacesPhase.READY]: строка «Авто» + каталог в серверном порядке.
 *
 * Выбор пишется в общий `mars-workspace-choice` префс (тем же
 * [WorkspaceSelection], что и чип чата), поэтому исходящие сообщения сразу
 * получают сентинел выбранного пространства; [ChatActivity] перечитывает чип в
 * `onResume` через [WorkspaceSelection.reloadFromStorage].
 *
 * Режим «Авто» отслеживается в рамках сессии экрана: контракт
 * [WorkspaceSelection.select]`(null)` при известном каталоге персистит принятое
 * серверное значение по умолчанию (на нём построен чип чата), поэтому «явного
 * пика нет» нельзя вывести из storage после нажатия «Авто». Начальный режим
 * берётся из storage при создании презентера: после пересоздания экрана
 * (поворот, повторный вход) «Авто» станет явным пиком серверного значения —
 * маршрут сообщений от этого не меняется, меняется только подпись строки.
 *
 * Конкурентные загрузки (быстрые «Обновить»): каждый [load] увеличивает
 * поколение, устаревший ответ не затирает свежий. Отмена корутины экрана
 * ([CancellationException]) пробрасывается дальше: отмена — не сетевой сбой.
 */
class SpacesPresenter(
    private val selection: WorkspaceSelection,
    private val gateway: SpacesGateway,
    private val isOffline: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Текущее состояние экрана; подписчик получает его через [subscribe]. */
    var state: SpacesUiState = SpacesUiState(phase = SpacesPhase.LOADING, message = SpacesPolicy.MSG_LOADING)
        private set

    /** Последний удачный каталог и время его получения — источник offline-safe показа. */
    private var catalog: WorkspacesResponse? = null
    private var catalogAtMs: Long? = null

    /** Режим выбора: «Авто» либо явный пик по пути пространства. */
    private var mode: SpacesMode =
        selection.current?.path?.takeIf { it.isNotBlank() }?.let(SpacesMode::Pick) ?: SpacesMode.Auto

    /** Поколение загрузки: результат устаревшего конкурентного [load] отбрасывается. */
    private var loadGeneration = 0

    private var onChanged: (SpacesUiState) -> Unit = {}

    /** Подписка на состояния; сразу реплеит текущее. Одна подписка на экран. */
    fun subscribe(listener: (SpacesUiState) -> Unit) {
        onChanged = listener
        listener(state)
    }

    /** Загрузка/перезагрузка каталога (первичная, «Обновить», retry). */
    suspend fun load() {
        val generation = ++loadGeneration
        publish(loadingState())

        val outcome = try {
            gateway.fetchSpaces()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            SpacesFetchResult.Failure
        }
        if (generation != loadGeneration) return
        publish(stateFor(outcome))
    }

    /**
     * Выбор строки по [SpaceRow.key]: [SpacesPolicy.AUTO_KEY] снимает явный пик
     * (маршрут выбирает сервер), иначе фиксирует пространство по пути. Возвращает
     * эффективный выбор после применения; null — пика нет (режим «Авто» без
     * серверного значения по умолчанию).
     */
    fun select(key: String): WorkspaceItem? {
        if (key == SpacesPolicy.AUTO_KEY) {
            mode = SpacesMode.Auto
            selection.select(null)
        } else {
            val item = catalog?.workspaces?.firstOrNull { it.path == key && it.path.isNotBlank() }
                ?: state.rows.firstOrNull { it.key == key && !it.isAuto }?.let { row ->
                    // Строка offline-safe списка: каталога нет, ключ и есть путь.
                    WorkspaceItem(name = row.title, path = row.key, isDefault = row.isServerDefault)
                }
                ?: return selection.current
            mode = SpacesMode.Pick(item.path)
            selection.select(item)
        }
        publish(state.copy(rows = state.rows.map { markSelection(it) }))
        return selection.current
    }

    private fun stateFor(outcome: SpacesFetchResult): SpacesUiState = when (outcome) {
        // Повтор доступен и здесь: после прохождения пары в другом экране каталог
        // откроется тем же «Повторить», без перезапуска приложения.
        is SpacesFetchResult.NotPaired -> SpacesUiState(
            phase = SpacesPhase.NO_AUTH,
            message = SpacesPolicy.MSG_NO_AUTH,
            canRetry = true,
        )
        is SpacesFetchResult.Failure -> failureState()
        is SpacesFetchResult.Success -> successState(outcome.response)
    }

    /** Обновление поверх готового каталога: список остаётся, меняется только шапка. */
    private fun loadingState(): SpacesUiState =
        if (state.rows.isEmpty()) {
            SpacesUiState(phase = SpacesPhase.LOADING, message = SpacesPolicy.MSG_LOADING)
        } else {
            // Фаза сохраняется (READY или ERROR с offline-safe строкой): иначе
            // повторная загрузка выдавала бы готовый каталог за отсутствие данных.
            state.copy(refreshing = true, stale = false, notice = null)
        }

    private fun successState(response: WorkspacesResponse): SpacesUiState {
        catalog = response
        catalogAtMs = clock()
        selection.resolveEffective(response)

        val picked = pickedPath()
        if (picked != null && response.workspaces.none { it.path == picked && it.path.isNotBlank() }) {
            // Выбранного пространства больше нет на сервере: держать явный пик на
            // несуществующем пути нельзя — следующее сообщение ушло бы с сентинелом
            // несуществующего каталога. Возвращаемся в «Авто» (решает сервер).
            mode = SpacesMode.Auto
            selection.select(null)
        }

        val catalogRows = SpacesPolicy.rows(response, pickedPath())
        return if (catalogRows.isEmpty()) {
            SpacesUiState(
                phase = SpacesPhase.EMPTY,
                summary = SpacesPolicy.summaryLine(response),
                message = SpacesPolicy.MSG_EMPTY,
            )
        } else {
            SpacesUiState(
                phase = SpacesPhase.READY,
                rows = listOf(SpacesPolicy.autoRow(pickedPath())) + catalogRows,
                summary = SpacesPolicy.summaryLine(response),
            )
        }
    }

    /**
     * Каталог не пришёл: свежий кэш сессии показывается как каталог с плашкой,
     * иначе — ошибка; при явном сохранённом выборе строка этого выбора остаётся
     * на экране, чтобы пользователь видел, какое пространство уйдёт в сообщение.
     */
    private fun failureState(): SpacesUiState {
        val offline = isOffline()
        val cached = catalog
        val cachedAt = catalogAtMs
        if (cached != null && cachedAt != null && SpacesPolicy.isCacheFresh(clock() - cachedAt)) {
            val picked = pickedPath()
            return SpacesUiState(
                phase = SpacesPhase.READY,
                rows = listOf(SpacesPolicy.autoRow(picked)) + SpacesPolicy.rows(cached, picked),
                summary = SpacesPolicy.summaryLine(cached),
                notice = if (offline) SpacesPolicy.NOTICE_OFFLINE_CACHE else SpacesPolicy.NOTICE_STALE_CACHE,
                canRetry = true,
                stale = true,
            )
        }

        // Только явный пик: серверное значение по умолчанию пользователь не выбирал,
        // и выдавать его за «сохранённый выбор» нельзя.
        val saved = if (mode is SpacesMode.Pick) selection.current?.takeIf { it.path.isNotBlank() } else null
        val rows = saved?.let { listOf(SpacesPolicy.localRow(it)) }.orEmpty()
        return SpacesUiState(
            phase = SpacesPhase.ERROR,
            rows = rows,
            notice = when {
                rows.isNotEmpty() && offline -> SpacesPolicy.NOTICE_OFFLINE_SAVED
                offline -> SpacesPolicy.NOTICE_OFFLINE
                else -> null
            },
            message = when {
                rows.isNotEmpty() -> null
                offline -> SpacesPolicy.MSG_ERROR_OFFLINE
                else -> SpacesPolicy.MSG_ERROR
            },
            canRetry = true,
        )
    }

    /** Путь явного пика для разметки строк; null в режиме «Авто». */
    private fun pickedPath(): String? = (mode as? SpacesMode.Pick)?.path

    /** Пересборка отметки «Активно» после выбора без повторной загрузки каталога. */
    private fun markSelection(row: SpaceRow): SpaceRow =
        if (row.isAuto) {
            row.copy(isActive = pickedPath() == null)
        } else {
            row.copy(isActive = row.key == pickedPath())
        }

    private fun publish(next: SpacesUiState) {
        state = next
        onChanged(next)
    }
}

/** Режим выбора пространства: отдать маршрут серверу или зафиксировать путь. */
private sealed interface SpacesMode {
    /** Пика нет: сентинел в сообщение не добавляется, маршрут выбирает сервер. */
    data object Auto : SpacesMode

    /** Явный пик пользователя. */
    data class Pick(val path: String) : SpacesMode
}
