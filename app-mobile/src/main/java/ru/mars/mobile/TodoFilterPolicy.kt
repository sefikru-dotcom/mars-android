package ru.mars.mobile

import ru.mars.network.model.TodoItem

/**
 * Фильтр-чипы статусов на экране «Задачи агента» (TodosActivity). Порядок объявления —
 * порядок чипов в ряду: [TodoFilterPolicy.CHIPS].
 */
enum class TodoFilter(val label: String) {
    ALL("Все"),
    IN_PROGRESS("В процессе"),
    COMPLETED("Выполнены"),
    PENDING("В очереди"),
}

/**
 * Чистая политика фильтрации списка задач (Android-free, для JVM-тестов): какие задачи
 * видны под выбранным чипом и какой текст показывать в пустом состоянии. Хост
 * (TodosActivity) только рендерит результат; активный чип выводится из идентичности
 * [TodoFilter], поэтому подсветка выбора не может разъехаться с фактическим фильтром.
 *
 * Предыстория (дефект): раньше стиль выбранного чипа вычислялся один раз при постройке
 * ряда — тап фильтровал список, но подсветка оставалась на «Все», и пользователь не видел,
 * какой фильтр активен.
 *
 * Правила:
 * - [TodoFilter.ALL] показывает всё, включая `cancelled` и неизвестные статусы;
 * - статусные фильтры сравнивают `TodoItem.status` дословно (значения сервера — в нижнем
 *   регистре, как в [ru.mars.network.model.TodoItem]);
 * - у `cancelled` нет своего чипа — отменённые задачи видны только под «Все».
 */
object TodoFilterPolicy {

    /** Статусы задач серверного снимка `todo_state` (значения [TodoItem.status]). */
    const val STATUS_PENDING = "pending"
    const val STATUS_IN_PROGRESS = "in_progress"
    const val STATUS_COMPLETED = "completed"
    const val STATUS_CANCELLED = "cancelled"

    /** Порядок и состав ряда чипов; Activity строит ряд ровно отсюда. */
    val CHIPS: List<TodoFilter> = TodoFilter.values().toList()

    /** Пустой экран: задач нет вообще. */
    const val EMPTY_NO_TASKS = "Задачи отсутствуют"

    /** Пустой экран: задачи есть, но под выбранный фильтр ни одна не подходит. */
    const val EMPTY_NONE_MATCHING = "Нет задач с выбранным статусом"

    /** Видна ли задача со статусом [status] под фильтром [filter]. */
    fun matches(filter: TodoFilter, status: String): Boolean = when (filter) {
        TodoFilter.ALL -> true
        TodoFilter.IN_PROGRESS -> status == STATUS_IN_PROGRESS
        TodoFilter.COMPLETED -> status == STATUS_COMPLETED
        TodoFilter.PENDING -> status == STATUS_PENDING
    }

    /** Задачи под выбранный чип; порядок снимка сохраняется. */
    fun visibleItems(items: List<TodoItem>, filter: TodoFilter): List<TodoItem> =
        items.filter { matches(filter, it.status) }

    /** Текст пустого состояния: список пуст сам по себе либо его сузил фильтр. */
    fun emptyText(allItemsEmpty: Boolean): String =
        if (allItemsEmpty) EMPTY_NO_TASKS else EMPTY_NONE_MATCHING
}
