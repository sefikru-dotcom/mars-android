package ru.mars.mobile.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import ru.mars.mobile.R

/**
 * Виджет «Марс — Быстрый ввод и голос» (компактная полоса 3x1 / 4x1).
 *
 * Одна строка для главного экрана — путь «спросить Марса» без открытия приложения:
 *
 * | зона               | тап                                                        |
 * |--------------------|------------------------------------------------------------|
 * | планета слева      | список диалогов ([ru.mars.mobile.ChatSessionsActivity])     |
 * | капсула ввода      | чат + клавиатура (`EXTRA_WIDGET_ACTION = "compose"`)        |
 * | микрофон           | чат + сразу диктовка (`EXTRA_WIDGET_ACTION = "voice"`)      |
 * | динамик            | живой голосовой режим Орба (полноэкранный разговор)         |
 * | звезда             | новый чистый чат (`EXTRA_WIDGET_ACTION = "new_chat"`)       |
 *
 * Состояния у компактного виджета нет намеренно: он занимает одну ячейку, и подпись
 * последнего диалога в нём отняла бы место у самого поля ввода. Но МАРШРУТИЗАЦИЯ берётся
 * из состояния полной панели: иначе тап с полосы вёл бы в серверный чат человеку, который
 * работает через прямой провайдер, — то есть в экран с запросом пароля вместо его диалога.
 *
 * Обновлять по таймеру нечего (`updatePeriodMillis = 0` в appwidget-provider): текстов
 * состояния здесь нет. Но «статичная разметка» НЕ значит «статичные действия»:
 * PendingIntent'ы собраны из снимка состояния (локальный или серверный чат, id последнего
 * диалога), поэтому полосу перерисовывает каждое изменение состояния —
 * [MarsQuickWidgetProvider.updateAllWidgets]. Без этого тап после смены режима или logout
 * открывал прошлую сессию.
 */
class MarsCompactWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // Состояние читается ОДИН раз на обновление: оно не зависит от экземпляра,
        // а каждый экземпляр — это отдельный IPC в процесс хоста.
        val state = MarsQuickWidgetProvider.readState(context)
        appWidgetIds.forEach { appWidgetId ->
            appWidgetManager.updateAppWidget(appWidgetId, buildRemoteViews(context, state))
        }
    }

    companion object {

        // --- RequestCode'ы: свой набор, чтобы не совпасть с PendingIntent'ами панели -------
        private const val REQUEST_SESSIONS = 7401
        private const val REQUEST_COMPOSE = 7402
        private const val REQUEST_VOICE = 7403
        private const val REQUEST_NEW_CHAT = 7404
        private const val REQUEST_VOICE_MODE = 7405

        /**
         * Собрать RemoteViews компактной полосы: пять тап-целей, ни одного лишнего view.
         *
         * [state] — снимок состояния панели: из него берётся только маршрутизация тапов
         * (локальный или серверный чат, id последнего диалога). Параметр необязателен,
         * потому что сборка из [onUpdate] и из общего обновления
         * ([MarsQuickWidgetProvider.updateAllWidgets]) не должна читать prefs дважды.
         */
        fun buildRemoteViews(
            context: Context,
            state: MarsWidgetActionsPolicy.MarsWidgetState =
                MarsQuickWidgetProvider.readState(context),
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_mars_compact)
            views.setTextViewText(
                R.id.widget_compact_input,
                context.getString(R.string.widget_mars_compose_hint),
            )
            // Режим решается один раз на сборку: проверка credential трогает Keystore.
            val localMode = MarsWidgetIntents.prefersLocal(context, state)

            // Пустое место капсулы делает то же, что поле ввода: чат и клавиатура.
            views.setOnClickPendingIntent(
                R.id.widget_compact_root,
                MarsWidgetIntents.activityPending(
                    context,
                    REQUEST_COMPOSE,
                    MarsWidgetIntents.activeChatIntent(
                        context,
                        MarsWidgetActionsPolicy.ACTION_COMPOSE,
                        state,
                        localMode,
                    ),
                ),
            )
            views.setOnClickPendingIntent(
                R.id.widget_compact_planet,
                MarsWidgetIntents.activityPending(
                    context,
                    REQUEST_SESSIONS,
                    MarsWidgetIntents.sessionListIntent(context),
                ),
            )
            views.setOnClickPendingIntent(
                R.id.widget_compact_input,
                MarsWidgetIntents.activityPending(
                    context,
                    REQUEST_COMPOSE,
                    MarsWidgetIntents.activeChatIntent(
                        context,
                        MarsWidgetActionsPolicy.ACTION_COMPOSE,
                        state,
                        localMode,
                    ),
                ),
            )
            views.setOnClickPendingIntent(
                R.id.widget_compact_mic,
                MarsWidgetIntents.activityPending(
                    context,
                    REQUEST_VOICE,
                    MarsWidgetIntents.activeChatIntent(
                        context,
                        MarsWidgetActionsPolicy.ACTION_VOICE,
                        state,
                        localMode,
                    ),
                ),
            )
            // Живой разговор: полноэкранный Орб сразу, без промежуточного экрана чата.
            views.setOnClickPendingIntent(
                R.id.widget_compact_voice_mode,
                MarsWidgetIntents.activityPending(
                    context,
                    REQUEST_VOICE_MODE,
                    MarsWidgetIntents.voiceModeIntent(context, state.sessionId),
                ),
            )
            views.setOnClickPendingIntent(
                R.id.widget_compact_star,
                MarsWidgetIntents.activityPending(
                    context,
                    REQUEST_NEW_CHAT,
                    MarsWidgetIntents.activeChatIntent(
                        context,
                        MarsWidgetActionsPolicy.ACTION_NEW_CHAT,
                        state,
                        localMode,
                    ),
                ),
            )
            return views
        }
    }
}
