package ru.mars.mobile

/**
 * Уровень внутри раздела [AppDestination.CHAT]. Список диалогов — отдельный
 * уровень, а не часть левого бара: бар остаётся приложенческой навигацией,
 * диалоги раскрываются только после выбора «Чата».
 *
 * Дочерние (сабагентные) сессии НЕ образуют собственный уровень и не
 * фильтруются: они раскрываются на карточке родительской сессии через
 * [Session.expandedChildIds]. Открытие дочерней сессии — это раскрытие
 * карточки, а не навигационный переход.
 */
sealed interface ChatLevel {
    /** Корневой уровень чата: список диалогов. */
    data object SessionList : ChatLevel

    /**
     * Открытый диалог. [expandedChildIds] — идентификаторы дочерних сессий,
     * раскрытых на карточке родителя; множество пусто по умолчанию и меняется
     * только [AppNavPolicy.toggleChildSession].
     */
    data class Session(
        val sessionId: String,
        val expandedChildIds: Set<String> = emptySet(),
    ) : ChatLevel {
        init {
            require(sessionId.isNotBlank()) { "sessionId не может быть пустым" }
        }
    }
}

/**
 * Полное навигационное состояние: выбранный раздел левого бара и уровень
 * внутри чата. Глубина намеренно ограничена двумя осями (раздел + уровень
 * чата), поэтому любой back/deep-link разрешается максимум в 2 перехода
 * (см. [AppNavPolicy.transitionsTo]).
 *
 * Инвариант: [chatLevel] отличен от [ChatLevel.SessionList] только при
 * [section] == [AppDestination.CHAT] — у остальных разделов нет уровня
 * диалогов.
 */
data class AppNavState(
    val section: AppDestination,
    val chatLevel: ChatLevel = ChatLevel.SessionList,
) {
    init {
        require(section == AppDestination.CHAT || chatLevel == ChatLevel.SessionList) {
            "chatLevel $chatLevel допустим только в разделе CHAT"
        }
    }

    companion object {
        /** Стартовое состояние: чат со списком диалогов. */
        val INITIAL = AppNavState(AppDestination.CHAT)
    }
}
