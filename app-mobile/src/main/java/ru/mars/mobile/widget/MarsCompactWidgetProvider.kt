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
 * | зона            | тап                                                        |
 * |-----------------|------------------------------------------------------------|
 * | планета слева   | список диалогов ([ru.mars.mobile.ChatSessionsActivity])     |
 * | капсула ввода   | чат + клавиатура (`EXTRA_WIDGET_ACTION = "compose"`)        |
 * | микрофон        | чат + сразу диктовка (`EXTRA_WIDGET_ACTION = "voice"`)      |
 * | звезда          | новый чистый чат (`EXTRA_WIDGET_ACTION = "new_chat"`)       |
 *
 * Состояния у компактного виджета нет намеренно: он занимает одну ячейку, и подпись
 * последнего диалога в нём отняла бы место у самого поля ввода. Живое состояние
 * показывает полная панель ([MarsQuickWidgetProvider]); здесь важна скорость.
 *
 * Обновлять по таймеру нечего (`updatePeriodMillis = 0` в appwidget-provider):
 * разметка статична, а действия — это PendingIntent'ы, которые живут вместе с виджетом.
 */
class MarsCompactWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { appWidgetId ->
            appWidgetManager.updateAppWidget(appWidgetId, buildRemoteViews(context))
        }
    }

    companion object {

        // --- RequestCode'ы: свой набор, чтобы не совпасть с PendingIntent'ами панели -------
        private const val REQUEST_SESSIONS = 7401
        private const val REQUEST_COMPOSE = 7402
        private const val REQUEST_VOICE = 7403
        private const val REQUEST_NEW_CHAT = 7404

        /** Собрать RemoteViews компактной полосы: четыре тап-цели, ни одного лишнего view. */
        fun buildRemoteViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_mars_compact)
            views.setTextViewText(
                R.id.widget_compact_input,
                context.getString(R.string.widget_mars_compose_hint),
            )

            // Пустое место капсулы делает то же, что поле ввода: чат и клавиатура.
            views.setOnClickPendingIntent(
                R.id.widget_compact_root,
                MarsWidgetIntents.activityPending(
                    context,
                    REQUEST_COMPOSE,
                    MarsWidgetIntents.chatIntent(context, MarsWidgetActionsPolicy.ACTION_COMPOSE),
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
                    MarsWidgetIntents.chatIntent(context, MarsWidgetActionsPolicy.ACTION_COMPOSE),
                ),
            )
            views.setOnClickPendingIntent(
                R.id.widget_compact_mic,
                MarsWidgetIntents.activityPending(
                    context,
                    REQUEST_VOICE,
                    MarsWidgetIntents.chatIntent(context, MarsWidgetActionsPolicy.ACTION_VOICE),
                ),
            )
            views.setOnClickPendingIntent(
                R.id.widget_compact_star,
                MarsWidgetIntents.activityPending(
                    context,
                    REQUEST_NEW_CHAT,
                    MarsWidgetIntents.chatIntent(context, MarsWidgetActionsPolicy.ACTION_NEW_CHAT),
                ),
            )
            return views
        }
    }
}
