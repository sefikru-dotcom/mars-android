package ru.mars.mobile.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import ru.mars.mobile.ChatActivity
import ru.mars.mobile.ChatSessionsActivity
import ru.mars.mobile.LocalChatActivity
import ru.mars.mobile.R
import ru.mars.mobile.VoiceModeActivity

/**
 * Виджет «Марс — Панель управления и последний чат» (4x2, масштабируется 4x1..4x3).
 *
 * Две половины, обе работают с домашнего экрана:
 *
 * **Состояние.** Верхняя часть показывает последний активный диалог: имя сессии и превью
 * последнего ответа агента. Источник — SharedPreferences [MarsWidgetActionsPolicy.STATE_PREFS],
 * куда ChatActivity пишет при смене диалога и при уходе экрана в фон, после чего зовёт
 * [notifyStateChanged]. Провайдер ничего не спрашивает у сервера: виджет живёт в процессе
 * лаунчера, и сетевой запрос отсюда был бы и медленным, и бессмысленным (экран чата и так
 * знает состояние точнее).
 *
 * **Действия.** Нижняя панель — четыре тапа, каждый доводит до нужного экрана без единого
 * промежуточного шага: капсула ввода (клавиатура), микрофон (сразу диктовка), живой голос
 * (локальный чат сразу в разговоре) и звезда (новый чистый чат).
 *
 * Почему PendingIntent.getActivity, а не broadcast: действие виджета — это НАВИГАЦИЯ.
 * Broadcast-приёмник в своём процессе пришлось бы поднимать приложение, ждать инициализации
 * credential-стора и только потом стартовать экран; система делает то же самое одним
 * переходом, и тап срабатывает мгновенно даже на холодном старте.
 *
 * Каждый тап получает СВОЙ requestCode: PendingIntent сравнивает интенты без extras, поэтому
 * «открыть диалог» и «новый чат» с одинаковым requestCode схлопнулись бы в один и тот же
 * PendingIntent, и второй тап молча повторял бы первый.
 */
class MarsQuickWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val state = readState(context)
        appWidgetIds.forEach { appWidgetId ->
            appWidgetManager.updateAppWidget(appWidgetId, buildRemoteViews(context, state))
        }
    }

    /**
     * Изменение размера (4x1..4x3): разметка одна, но перерисовка нужна — лаунчер после
     * ресайза показывает старый RemoteViews до первого обновления.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        appWidgetManager.updateAppWidget(appWidgetId, buildRemoteViews(context, readState(context)))
    }

    companion object {

        // --- RequestCode'ы: по одному на каждое действие (см. KDoc класса) -------------------
        private const val REQUEST_DIALOG = 7301
        private const val REQUEST_SESSIONS = 7302
        private const val REQUEST_COMPOSE = 7303
        private const val REQUEST_VOICE = 7304
        private const val REQUEST_VOICE_MODE = 7305
        private const val REQUEST_NEW_CHAT = 7306

        /**
         * Сохранить живое состояние и перерисовать все экземпляры виджета.
         *
         * Вызывается из ChatActivity: смена диалога, приход ответа агента, уход экрана в фон.
         * `apply()` (а не `commit()`) — запись идёт с главного потока экрана, и ждать диск
         * ради подписи в виджете нельзя.
         */
        fun publishState(
            context: Context,
            sessionId: String?,
            sessionTitle: String?,
            lastSnippet: String?,
        ) {
            context.getSharedPreferences(MarsWidgetActionsPolicy.STATE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(MarsWidgetActionsPolicy.KEY_SESSION_ID, sessionId)
                .putString(MarsWidgetActionsPolicy.KEY_SESSION_TITLE, sessionTitle)
                .putString(MarsWidgetActionsPolicy.KEY_LAST_SNIPPET, lastSnippet)
                .putLong(MarsWidgetActionsPolicy.KEY_UPDATED_AT, System.currentTimeMillis())
                .apply()
            notifyStateChanged(context)
        }

        /** Перерисовать виджет по уже сохранённому состоянию. */
        fun notifyStateChanged(context: Context) {
            updateAllWidgets(context)
        }

        /**
         * Контрактная форма «сохранить и обновить всё» (`CONTRACT-WAVE17`, Поток 3):
         * те же три значения, что виджет показывает, — адресат тапа, имя диалога и превью.
         * Тонкая обёртка над [publishState], чтобы вызывающая сторона не решала, в каком
         * порядке писать prefs и перерисовывать.
         */
        fun updateAllWidgets(
            context: Context,
            sessionId: String?,
            sessionTitle: String?,
            lastSnippet: String?,
        ) {
            publishState(context, sessionId, sessionTitle, lastSnippet)
        }

        /**
         * Обновить все экземпляры виджета на всех домашних экранах.
         *
         * Пустой список id — норма (виджет не добавлен): выход без работы, чтобы вызов из
         * onPause не стоил ничего пользователю, который виджет не ставил.
         */
        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, MarsQuickWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val state = readState(context)
            ids.forEach { appWidgetId ->
                manager.updateAppWidget(appWidgetId, buildRemoteViews(context, state))
            }
        }

        /** Состояние из SharedPreferences → нормализованное политикой (дефолты и обрезка). */
        fun readState(context: Context): MarsWidgetActionsPolicy.MarsWidgetState {
            val prefs = context.getSharedPreferences(MarsWidgetActionsPolicy.STATE_PREFS, Context.MODE_PRIVATE)
            return MarsWidgetActionsPolicy.state(
                sessionId = prefs.getString(MarsWidgetActionsPolicy.KEY_SESSION_ID, null),
                rawTitle = prefs.getString(MarsWidgetActionsPolicy.KEY_SESSION_TITLE, null),
                rawSnippet = prefs.getString(MarsWidgetActionsPolicy.KEY_LAST_SNIPPET, null),
                updatedAt = prefs.getLong(MarsWidgetActionsPolicy.KEY_UPDATED_AT, 0L),
            )
        }

        /**
         * Собрать RemoteViews: тексты состояния + шесть тап-целей.
         *
         * Разметка читает те же строки из ресурсов, что и здесь, поэтому виджет корректен
         * даже до первого обновления состояния (initialLayout в appwidget-provider).
         */
        fun buildRemoteViews(
            context: Context,
            state: MarsWidgetActionsPolicy.MarsWidgetState,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_mars_quick)
            views.setTextViewText(R.id.widget_mars_brand, context.getString(R.string.widget_mars_label))
            views.setTextViewText(R.id.widget_mars_sessions, context.getString(R.string.widget_mars_sessions))
            views.setTextViewText(R.id.widget_mars_input, context.getString(R.string.widget_mars_compose_hint))
            views.setTextViewText(R.id.widget_mars_session_title, state.title)
            views.setTextViewText(R.id.widget_mars_snippet, state.snippet)
            views.setTextViewText(
                R.id.widget_mars_updated,
                MarsWidgetActionsPolicy.updatedLabel(state.updatedAt),
            )

            // Блок диалога: есть сессия — открываем ИМЕННО её; нет — новый чат с клавиатурой.
            // Ведение в несуществующую сессию было бы худшим ответом на тап.
            val dialogIntent = state.sessionId
                ?.let { MarsWidgetIntents.openSessionIntent(context, it) }
                ?: MarsWidgetIntents.chatIntent(context, MarsWidgetActionsPolicy.ACTION_COMPOSE)
            views.setOnClickPendingIntent(
                R.id.widget_mars_dialog,
                MarsWidgetIntents.activityPending(context, REQUEST_DIALOG, dialogIntent),
            )
            // Тап по пустому месту карточки — тот же смысл, что у блока диалога.
            views.setOnClickPendingIntent(
                R.id.widget_mars_root,
                MarsWidgetIntents.activityPending(context, REQUEST_DIALOG, dialogIntent),
            )
            views.setOnClickPendingIntent(
                R.id.widget_mars_sessions,
                MarsWidgetIntents.activityPending(
                    context,
                    REQUEST_SESSIONS,
                    MarsWidgetIntents.sessionListIntent(context),
                ),
            )
            views.setOnClickPendingIntent(
                R.id.widget_mars_input,
                MarsWidgetIntents.activityPending(
                    context,
                    REQUEST_COMPOSE,
                    MarsWidgetIntents.chatIntent(context, MarsWidgetActionsPolicy.ACTION_COMPOSE),
                ),
            )
            views.setOnClickPendingIntent(
                R.id.widget_mars_mic,
                MarsWidgetIntents.activityPending(
                    context,
                    REQUEST_VOICE,
                    MarsWidgetIntents.chatIntent(context, MarsWidgetActionsPolicy.ACTION_VOICE),
                ),
            )
            views.setOnClickPendingIntent(
                R.id.widget_mars_voice_mode,
                MarsWidgetIntents.activityPending(
                    context,
                    REQUEST_VOICE_MODE,
                    MarsWidgetIntents.voiceModeIntent(context, state.sessionId),
                ),
            )
            views.setOnClickPendingIntent(
                R.id.widget_mars_star,
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

/**
 * Интенты и PendingIntent'ы тапов — один набор на оба виджета.
 *
 * Почему общий объект, а не приватные функции в каждом провайдере: у полной панели и
 * компактной полосы действия одни и те же, и расхождение в флагах (забытый NEW_TASK)
 * проявилось бы только на устройстве — причём в одном виджете из двух.
 */
internal object MarsWidgetIntents {

    /**
     * Интент уровня диалога с действием виджета.
     *
     * NEW_TASK обязателен (старт не из Activity), CLEAR_TOP|SINGLE_TOP — чтобы уже
     * открытый чат получил onNewIntent и применил действие, а не открылся второй копией
     * поверх первого.
     */
    fun chatIntent(context: Context, action: String?): Intent =
        Intent(context, ChatActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            .also { intent ->
                if (action != null) {
                    intent.putExtra(MarsWidgetActionsPolicy.EXTRA_WIDGET_ACTION, action)
                }
            }

    /** Открыть конкретный диалог (тап по блоку последнего чата). */
    fun openSessionIntent(context: Context, sessionId: String): Intent =
        chatIntent(context, null).putExtra(ChatActivity.EXTRA_OPEN_SESSION_ID, sessionId)

    /** Уровень списка диалогов — корень раздела CHAT. */
    fun sessionListIntent(context: Context): Intent =
        Intent(context, ChatSessionsActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )

    /**
     * Полноэкранный живой голос: последний диалог сразу в разговоре.
     *
     * Ведёт в [LocalChatActivity], а не в [VoiceModeActivity]: витрина без движков не включает
     * микрофон, и тап «живой голос» с домашнего экрана заканчивался пустым орбом. Флаг
     * [VoiceModeActivity.EXTRA_OPEN_VOICE_MODE] заставляет чат открыть разговор сразу.
     *
     * Флаги как у остальных тапов виджета: NEW_TASK обязателен (старт не из Activity), а
     * CLEAR_TOP|SINGLE_TOP — чтобы уже открытый чат получил `onNewIntent` и включил разговор,
     * а не открылся второй копией поверх первого.
     */
    fun voiceModeIntent(context: Context, sessionId: String? = null): Intent =
        LocalChatActivity.newIntent(context, sessionId)
            .putExtra(VoiceModeActivity.EXTRA_OPEN_VOICE_MODE, true)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )

    /**
     * PendingIntent тапа. FLAG_IMMUTABLE обязателен с Android 12 (иначе SecurityException
     * на старте), FLAG_UPDATE_CURRENT — чтобы изменившееся состояние (другой session_id)
     * доехало до уже выданного PendingIntent'а.
     */
    fun activityPending(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
