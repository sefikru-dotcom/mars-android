package ru.mars.mobile

import android.content.Context
import android.content.Intent

/**
 * UI-слой навигационной модели: сопоставление [AppDestination], [NavTransition]
 * и [AppDeepLink] реальным Intent-переходам между Activity. Чистая политика
 * ([AppNavPolicy], [AppNavState], [AppSectionsPolicy]) не знает об экранах —
 * здесь единственная таблица «раздел → Activity» (заменяет legacy-маршрутизатор
 * NavTarget → Activity из смешанной шторки ChatActivity).
 *
 * Двухуровневый контракт стека: список диалогов (корень CHAT,
 * [ChatSessionsActivity]) всегда НИЖЕ уровня диалога ([ChatActivity]);
 * переход «чат → диалоги» переиспользует существующий корень через
 * CLEAR_TOP|SINGLE_TOP, поэтому глубина стека в chat-потоке не превышает
 * двух экранов ([AppNavPolicy.MAX_TRANSITIONS]).
 */
object AppNavIntents {

    /**
     * Уровень списка диалогов — корень раздела [AppDestination.CHAT].
     * CLEAR_TOP|SINGLE_TOP: существующий [ChatSessionsActivity] переиспользуется
     * (onNewIntent), всё выше него в стеке (открытый диалог) закрывается.
     */
    fun sessionListIntent(context: Context): Intent =
        Intent(context, ChatSessionsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Уровень диалога: [ChatActivity] без открытой сессии («новый чат»). */
    fun chatIntent(context: Context): Intent = Intent(context, ChatActivity::class.java)

    /** Уровень диалога: [ChatActivity] с открытым диалогом [sessionId]. */
    fun openSessionIntent(context: Context, sessionId: String): Intent {
        require(sessionId.isNotBlank()) { "sessionId не может быть пустым" }
        return chatIntent(context).putExtra(ChatActivity.EXTRA_OPEN_SESSION_ID, sessionId)
    }

    /**
     * Intent раздела левого бара. [chatSessionId] пробрасывается в «Задачи»
     * (контекст текущего диалога — паритет с прежним поведением); для CHAT
     * возвращает корень раздела (уровень списка диалогов).
     */
    fun sectionIntent(
        context: Context,
        destination: AppDestination,
        chatSessionId: String? = null,
    ): Intent = when (destination) {
        AppDestination.CHAT -> sessionListIntent(context)
        AppDestination.TASKS -> Intent(context, TodosActivity::class.java)
            .putExtra("EXTRA_SESSION_ID", chatSessionId)
        AppDestination.SCHEDULER -> Intent(context, CronActivity::class.java)
        AppDestination.PROJECTS -> Intent(context, ProjectsActivity::class.java)
        AppDestination.MUSIC -> Intent(context, MusicActivity::class.java)
        AppDestination.MEMORY -> Intent(context, MemoryActivity::class.java)
        AppDestination.SKILLS -> Intent(context, SkillsActivity::class.java)
        AppDestination.KANBAN -> KanbanActivity.newIntent(context)
        AppDestination.ANALYTICS -> InsightsActivity.newIntent(context)
        // Каталог пространств и выбор рабочего контекста для следующих сообщений.
        AppDestination.SPACES -> Intent(context, SpacesActivity::class.java)
        AppDestination.SETTINGS -> Intent(context, SettingsActivity::class.java)
    }

    /**
     * Элементарный переход модели ([NavTransition] — результат
     * [AppNavPolicy.transitionsTo]/[AppNavPolicy.apply]) → реальный Intent.
     */
    fun transitionIntent(
        context: Context,
        transition: NavTransition,
        chatSessionId: String? = null,
    ): Intent = when (transition) {
        is NavTransition.SelectSection -> sectionIntent(context, transition.destination, chatSessionId)
        is NavTransition.OpenSession -> openSessionIntent(context, transition.sessionId)
    }

    /**
     * Deep-link ([AppDeepLink]) → последовательность Intent-переходов из
     * [AppNavState.INITIAL]: не более [AppNavPolicy.MAX_TRANSITIONS] целей.
     * Пустой список — цель уже текущая (корень CHAT хостит сам вызывающий
     * экран списка диалогов).
     */
    fun deepLinkIntents(context: Context, link: AppDeepLink): List<Intent> =
        AppNavPolicy.transitionsTo(AppNavState.INITIAL, link.toNavState())
            .filterNot { it == NavTransition.SelectSection(AppDestination.CHAT) }
            .map { transitionIntent(context, it) }
}
