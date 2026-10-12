package ru.mars.mobile

import ru.mars.network.model.WorkspaceItem
import ru.mars.network.model.WorkspacesResponse

/**
 * Презентер экрана Spaces: оркестрирует загрузку каталога (порт [fetch] —
 * в app это `WorkspaceRepository().getWorkspaces(...)`), разрешение выбора
 * через [WorkspaceSelection] и offline-safe состояния. Чистый JVM-класс без
 * Android-импортов: сеть, хранилище и детектор офлайна инжектятся, поэтому
 * переходы состояний полностью покрыты unit-тестами.
 *
 * Контракт состояний:
 * - нет credential -> [SpacesPhase.NO_AUTH] (загрузка даже не начинается);
 * - fetch упал/вернул null -> [SpacesPhase.ERROR]; если сохранён явный выбор,
 *   он показывается локальной строкой (offline-safe) вместо пустого экрана;
 * - пустой каталог -> [SpacesPhase.EMPTY];
 * - успех -> [SpacesPhase.READY]: строка «Авто» + каталог в серверном порядке,
 *   `resolveEffective` синхронизирует эффективный выбор (persisted-пик побеждает).
 */
class SpacesPresenter(
    private val selection: WorkspaceSelection,
    private val explicitPickPath: () -> String?,
    private val hasCredential: () -> Boolean,
    private val fetch: suspend () -> WorkspacesResponse?,
    private val isOffline: () -> Boolean = { false },
) {

    var state: SpacesUiState = SpacesUiState(
        phase = SpacesPhase.LOADING,
        rows = emptyList(),
        message = SpacesPolicy.MSG_LOADING,
    )
        private set

    private var catalog: WorkspacesResponse? = null
    private var onChanged: (SpacesUiState) -> Unit = {}

    /**
     * Режим «Авто» в рамках сессии. Важно: [WorkspaceSelection.select]`(null)`
     * при известном каталоге ПЕРСИСТИТ принятый серверный default (существующий
     * контракт, на нём же построен чип ChatActivity), поэтому «нет явного пика»
     * нельзя вывести из storage после выбора «Авто» — отслеживаем здесь.
     * Старт: «Авто», пока в storage нет сохранённого пика.
     */
    private var autoSelected: Boolean = explicitPickPath() == null

    /** Путь явного пика для разметки строк; null в режиме «Авто». */
    private fun pickedPathForUi(): String? = if (autoSelected) null else explicitPickPath()

    /** Подписка на состояния; сразу реплеит текущее. Одна подписка на экран. */
    fun subscribe(listener: (SpacesUiState) -> Unit) {
        onChanged = listener
        listener(state)
    }

    /** Загрузка/перезагрузка каталога. Повторные вызовы идемпотентны по состоянию. */
    suspend fun load() {
        if (!hasCredential()) {
            publish(
                SpacesUiState(
                    phase = SpacesPhase.NO_AUTH,
                    rows = emptyList(),
                    message = SpacesPolicy.MSG_NO_AUTH,
                )
            )
            return
        }
        val previousRows = state.rows
        publish(
            SpacesUiState(
                phase = SpacesPhase.LOADING,
                rows = previousRows,
                message = if (previousRows.isEmpty()) SpacesPolicy.MSG_LOADING else null,
            )
        )
        val response = runCatching { fetch() }.getOrNull()
        if (response == null) {
            publish(failureState())
            return
        }
        catalog = response
        if (response.workspaces.isEmpty()) {
            publish(SpacesUiState(phase = SpacesPhase.EMPTY, rows = emptyList(), message = SpacesPolicy.MSG_EMPTY))
            return
        }
        selection.resolveEffective(response)
        publish(readyState())
    }

    /**
     * Выбор строки по [SpaceRowUi.key]: [SpacesPolicy.AUTO_KEY] снимает явный пик
     * (серверный default), иначе фиксирует пространство. Персистит через
     * [WorkspaceSelection] — тот же storage `mars-workspace-choice`, который
     * читает ChatActivity, поэтому выбор сразу влияет на исходящие сообщения.
     * Возвращает эффективный выбор после применения.
     */
    fun select(key: String): WorkspaceItem? {
        if (key == SpacesPolicy.AUTO_KEY) {
            autoSelected = true
            selection.select(null)
        } else {
            val item = catalog?.workspaces?.firstOrNull { it.path == key }
            if (item != null) {
                autoSelected = false
                selection.select(item)
            } else {
                // Строка из offline-safe локального списка: ключ и есть путь.
                val local = state.rows.firstOrNull { it.key == key && !it.isAuto }
                if (local != null) {
                    autoSelected = false
                    selection.select(WorkspaceItem(name = local.title, path = local.key, isDefault = local.isServerDefault))
                } else {
                    return selection.current
                }
            }
        }
        publish(if (catalog != null && state.phase == SpacesPhase.READY) readyState() else state.copy(
            rows = rebuildRowsForCurrentPhase(),
        ))
        return selection.current
    }

    private fun readyState(notice: String? = null): SpacesUiState {
        val response = catalog ?: return state
        val picked = pickedPathForUi()
        val rows = listOf(SpacesPolicy.autoRow(picked)) + SpacesPolicy.rows(response, picked)
        return SpacesUiState(
            phase = SpacesPhase.READY,
            rows = rows,
            notice = notice,
            message = null,
        )
    }

    private fun failureState(): SpacesUiState {
        val offline = isOffline()
        val saved = selection.current?.takeIf { pickedPathForUi() != null }
        val rows = if (saved != null) listOf(SpacesPolicy.localRow(saved)) else emptyList()
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

    /**
     * Пересборка строк вне READY (offline-safe локальная строка): отметка
     * «Активно» следует за текущим эффективным выбором.
     */
    private fun rebuildRowsForCurrentPhase(): List<SpaceRowUi> =
        state.rows.map { row ->
            if (row.isAuto) {
                row.copy(isActive = pickedPathForUi().isNullOrBlank())
            } else {
                row.copy(isActive = pickedPathForUi() == row.key)
            }
        }

    private fun publish(next: SpacesUiState) {
        state = next
        onChanged(next)
    }
}