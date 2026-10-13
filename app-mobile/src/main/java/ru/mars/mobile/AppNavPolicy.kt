package ru.mars.mobile

/**
 * Элементарный навигационный переход. Переходов ровно два вида, поэтому любая
 * цель достижима максимум за [AppNavPolicy.MAX_TRANSITIONS] = 2 шага:
 * выбор раздела бара (он же — сброс на корень раздела) и открытие диалога.
 */
sealed interface NavTransition {
    /** Выбор раздела левого бара; приводит раздел к его корневому уровню. */
    data class SelectSection(val destination: AppDestination) : NavTransition

    /** Открытие диалога внутри [AppDestination.CHAT]. */
    data class OpenSession(val sessionId: String) : NavTransition
}

/**
 * Чистая политика навигации «левый бар ↔ диалоги». Не знает об Activity,
 * Fragment и View: состояние — [AppNavState], сопоставление экранам живёт в
 * UI-слое. Правила:
 *
 * 1. Левый бар — только приложенческие разделы ([AppDestination]); клики по
 *    бару не накапливают стек, а переводят раздел в корень (дубли входов
 *    сокращаются: повторный выбор текущего раздела идемпотентен).
 * 2. Список диалогов — корневой уровень раздела [AppDestination.CHAT],
 *    диалог — второй уровень. Глубже двух уровней навигация не уходит.
 * 3. Дочерние (сабагентные) сессии не фильтруются и не образуют уровень:
 *    они раскрываются на карточке родителя ([AppNavPolicy.toggleChildSession]).
 * 4. Back из диалога ведёт в список диалогов; на корневом уровне back
 *    отсутствует (null) — выход из приложения решает UI-слой.
 * 5. Logout имеет единственную точку входа — [AppNavPolicy.LOGOUT_HOST];
 *    из других разделов выход не предлагается.
 */
object AppNavPolicy {

    /** Продуктовый лимит: любой back/deep-link разрешается максимум за 2 перехода. */
    const val MAX_TRANSITIONS = 2

    /** Единственный раздел, хостящий logout (сокращение дублей входов). */
    val LOGOUT_HOST: AppDestination = AppDestination.SETTINGS

    /**
     * Клик по разделу левого бара: раздел становится текущим и сбрасывается
     * на корневой уровень. Идемпотентен: повторный выбор текущего раздела
     * с корневым уровнем не изменяет состояние.
     */
    fun selectSection(state: AppNavState, destination: AppDestination): AppNavState =
        AppNavState(destination)

    /**
     * Открытие диалога. Допустимо только в разделе [AppDestination.CHAT] —
     * иначе состояние не меняется (список диалогов не покидает чат).
     * Повторное открытие того же диалога идемпотентно; открытие другого
     * диалога заменяет текущий без роста глубины.
     */
    fun openSession(state: AppNavState, sessionId: String): AppNavState {
        require(sessionId.isNotBlank()) { "sessionId не может быть пустым" }
        if (state.section != AppDestination.CHAT) return state
        val current = state.chatLevel
        if (current is ChatLevel.Session && current.sessionId == sessionId) return state
        return AppNavState(AppDestination.CHAT, ChatLevel.Session(sessionId))
    }

    /**
     * Раскрытие/сворачивание дочерней сессии НА КАРТОЧКЕ родителя: меняет
     * только [ChatLevel.Session.expandedChildIds], уровень навигации не
     * добавляется. Вне открытого диалога — no-op. Никакого фильтра субагентов:
     * политика не решает, каких детей показывать, — только где.
     */
    fun toggleChildSession(state: AppNavState, childSessionId: String): AppNavState {
        require(childSessionId.isNotBlank()) { "childSessionId не может быть пустым" }
        val level = state.chatLevel
        if (state.section != AppDestination.CHAT || level !is ChatLevel.Session) return state
        val updated = if (childSessionId in level.expandedChildIds) {
            level.expandedChildIds - childSessionId
        } else {
            level.expandedChildIds + childSessionId
        }
        return state.copy(chatLevel = level.copy(expandedChildIds = updated))
    }

    /**
     * Правило back: из диалога — в список диалогов; с корневого уровня —
     * null (дальше некуда, бар и корень раздела остаются на месте).
     */
    fun back(state: AppNavState): AppNavState? = when (state.chatLevel) {
        is ChatLevel.Session -> AppNavState(AppDestination.CHAT)
        ChatLevel.SessionList -> null
    }

    /**
     * Кратчайшая последовательность переходов из [current] в [target]
     * (для deep-link и восстановления состояния). Результат всегда содержит
     * не более [MAX_TRANSITIONS] элементов; пустой список — цель уже текущая.
     */
    fun transitionsTo(current: AppNavState, target: AppNavState): List<NavTransition> {
        val transitions = mutableListOf<NavTransition>()
        val currentLevel = current.chatLevel
        val targetLevel = target.chatLevel
        val needsSectionSwitch = current.section != target.section ||
            (targetLevel == ChatLevel.SessionList && currentLevel is ChatLevel.Session)
        if (needsSectionSwitch) {
            transitions += NavTransition.SelectSection(target.section)
        }
        // Раскрытие дочерних карточек (expandedChildIds) — не маршрутная ось:
        // переходы сравнивают сессии только по sessionId.
        val currentSessionId = (currentLevel as? ChatLevel.Session)?.sessionId
        val targetSessionId = (targetLevel as? ChatLevel.Session)?.sessionId
        if (targetSessionId != null && currentSessionId != targetSessionId) {
            transitions += NavTransition.OpenSession(targetSessionId)
        }
        check(transitions.size <= MAX_TRANSITIONS) {
            "Нарушен контракт: ${transitions.size} переходов (лимит $MAX_TRANSITIONS)"
        }
        return transitions
    }

    /** Применение последовательности переходов к состоянию. */
    fun apply(state: AppNavState, transitions: List<NavTransition>): AppNavState =
        transitions.fold(state) { acc, transition ->
            when (transition) {
                is NavTransition.SelectSection -> selectSection(acc, transition.destination)
                is NavTransition.OpenSession -> openSession(acc, transition.sessionId)
            }
        }

    /** Единственная точка входа logout — только [LOGOUT_HOST]. */
    fun isLogoutAvailable(section: AppDestination): Boolean = section == LOGOUT_HOST
}
