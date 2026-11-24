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
import ru.mars.mobile.LocalMemoryActivity
import ru.mars.mobile.LocalProviderPrefsStore
import ru.mars.mobile.MemoryActivity
import ru.mars.mobile.MusicActivity
import ru.mars.mobile.R
import ru.mars.mobile.TodosActivity
import ru.mars.mobile.VoiceModeActivity
import ru.mars.network.credential.AndroidKeyStoreCredentialStore

/**
 * Виджет «Марс — Панель управления и последний чат» (4x2, масштабируется 4x1..4x3).
 *
 * Четыре полосы сверху вниз, и каждая отвечает на свой вопрос с домашнего экрана:
 *
 * 1. **Шапка** — планета, бренд «МАРС», пилюля текущей модели/режима, время последнего
 *    обновления и «Диалоги». Модель и режим — из того же снимка состояния, что и текст
 *    карточки: подпись «gemini-3.8-flash» рядом с ответом сервера была бы ложью.
 * 2. **Карточка контекста** — имя последнего диалога и две строки последнего ответа.
 *    Источник — SharedPreferences [MarsWidgetActionsPolicy.STATE_PREFS], куда пишут ОБА
 *    чата: серверный `ChatActivity` и локальный `LocalChatActivity`. Провайдер ничего не
 *    спрашивает у сети: состояние знает экран чата, а лаунчер только рисует присланные
 *    RemoteViews — провайдер исполняется в процессе приложения, но его результат живёт
 *    в чужом процессе, и «спросить» оттуда уже ничего нельзя.
 * 3. **Композер** — капсула ввода (клавиатура), диктовка, живой голос Орба и звезда нового
 *    чистого чата.
 * 4. **Быстрые инструменты** — четыре стеклянные пилюли в один тап: «Локально», «Задачи»,
 *    «Память», «Музыка». Это разделы, куда человек ходит каждый день, и ради них не нужно
 *    ни открывать приложение, ни вспоминать, где что лежит.
 *
 * **Железная маршрутизация.** Главный дефект прошлой версии — «нажимаю, а он просто
 * открывает приложение»: тап вёл в серверный `ChatActivity` даже когда человек живёт в
 * локальном чате (без серверного логина), и экран молча редиректил на ввод пароля.
 * Поэтому решение принимает [MarsWidgetIntents.activeChatIntent] по трём фактам:
 * `state.isLocal`, наличие серверного device-credential и наличие активного профиля
 * прямого провайдера. Серверный чат — `ChatActivity`, локальный — `LocalChatActivity`.
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
        private const val REQUEST_CHIP_LOCAL = 7307
        private const val REQUEST_CHIP_TASKS = 7308
        private const val REQUEST_CHIP_MEMORY = 7309
        private const val REQUEST_CHIP_MUSIC = 7310
        private const val REQUEST_MODEL_BADGE = 7311

        /**
         * Сохранить живое состояние и перерисовать все экземпляры виджета.
         *
         * Вызывается из ОБОИХ чатов: `ChatActivity` (смена диалога, приход ответа агента,
         * уход экрана в фон) и `LocalChatActivity` (те же точки, плюс смена модели).
         * `apply()` (а не `commit()`) — запись идёт с главного потока экрана, и ждать диск
         * ради подписи в виджете нельзя.
         *
         * [modelLabel] и [isLocal] — необязательные: старые четырёхаргументные вызовы
         * продолжают компилироваться и работать, получая пилюлю [MarsWidgetActionsPolicy.DEFAULT_MODEL_LABEL]
         * и серверный режим. Имя модели нормализует политика ([MarsWidgetActionsPolicy.modelLabel]),
         * а не вызывающая сторона: обрезка и отсечение пути провайдера обязаны быть одни на всех.
         */
        fun publishState(
            context: Context,
            sessionId: String?,
            sessionTitle: String?,
            lastSnippet: String?,
            modelLabel: String? = null,
            isLocal: Boolean = false,
        ) {
            context.getSharedPreferences(MarsWidgetActionsPolicy.STATE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(MarsWidgetActionsPolicy.KEY_SESSION_ID, sessionId)
                .putString(MarsWidgetActionsPolicy.KEY_SESSION_TITLE, sessionTitle)
                .putString(MarsWidgetActionsPolicy.KEY_LAST_SNIPPET, lastSnippet)
                .putLong(MarsWidgetActionsPolicy.KEY_UPDATED_AT, System.currentTimeMillis())
                .putString(
                    MarsWidgetActionsPolicy.KEY_MODEL_LABEL,
                    MarsWidgetActionsPolicy.modelLabel(modelLabel, isLocal),
                )
                .putBoolean(MarsWidgetActionsPolicy.KEY_IS_LOCAL, isLocal)
                .apply()
            notifyStateChanged(context)
        }

        /** Перерисовать виджет по уже сохранённому состоянию. */
        fun notifyStateChanged(context: Context) {
            updateAllWidgets(context)
        }

        /**
         * Контрактная форма «сохранить и обновить всё» (`CONTRACT-WAVE17`, Поток 3,
         * расширена в волне 21): те же значения, что виджет показывает, — адресат тапа,
         * имя диалога, превью, пилюля модели и признак локального режима.
         * Тонкая обёртка над [publishState], чтобы вызывающая сторона не решала, в каком
         * порядке писать prefs и перерисовывать.
         */
        fun updateAllWidgets(
            context: Context,
            sessionId: String?,
            sessionTitle: String?,
            lastSnippet: String?,
            modelLabel: String? = null,
            isLocal: Boolean = false,
        ) {
            publishState(context, sessionId, sessionTitle, lastSnippet, modelLabel, isLocal)
        }

        /**
         * Обновить все экземпляры ОБОИХ виджетов на всех домашних экранах.
         *
         * Компактная полоса обновляется здесь же, хотя состояния не показывает: её
         * PendingIntent'ы собраны из снимка состояния (локальный или серверный чат, id
         * последнего диалога). Пока полоса перерисовывалась только по APPWIDGET_UPDATE
         * (добавление, перезагрузка, обновление пакета), после смены режима или диалога
         * её тапы вели в прошлую сессию — и после logout продолжали открывать её.
         *
         * Пустой список id — норма (виджет не добавлен): обход пустого массива не стоит
         * ничего, поэтому вызов из onPause безопасен и для человека без виджетов.
         */
        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val state = readState(context)
            manager.getAppWidgetIds(ComponentName(context, MarsQuickWidgetProvider::class.java))
                .forEach { appWidgetId ->
                    manager.updateAppWidget(appWidgetId, buildRemoteViews(context, state))
                }
            manager.getAppWidgetIds(ComponentName(context, MarsCompactWidgetProvider::class.java))
                .forEach { appWidgetId ->
                    manager.updateAppWidget(
                        appWidgetId,
                        MarsCompactWidgetProvider.buildRemoteViews(context, state),
                    )
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
                rawModel = prefs.getString(MarsWidgetActionsPolicy.KEY_MODEL_LABEL, null),
                isLocal = prefs.getBoolean(MarsWidgetActionsPolicy.KEY_IS_LOCAL, false),
            )
        }

        /**
         * Собрать RemoteViews: тексты состояния + одиннадцать тап-целей.
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
            views.setTextViewText(R.id.widget_mars_model_badge, state.modelLabel)
            views.setTextViewText(
                R.id.widget_mars_updated,
                MarsWidgetActionsPolicy.updatedLabel(state.updatedAt),
            )
            views.setTextViewText(
                R.id.widget_mars_chip_local,
                context.getString(R.string.widget_mars_chip_local),
            )
            views.setTextViewText(
                R.id.widget_mars_chip_tasks,
                context.getString(R.string.widget_mars_chip_tasks),
            )
            views.setTextViewText(
                R.id.widget_mars_chip_memory,
                context.getString(R.string.widget_mars_chip_memory),
            )
            views.setTextViewText(
                R.id.widget_mars_chip_music,
                context.getString(R.string.widget_mars_chip_music),
            )

            // Куда ведут тапы «продолжить разговор» — решается ОДИН раз на сборку: проверка
            // серверного credential трогает Keystore, и шесть её вызовов подряд на каждое
            // обновление виджета были бы дороже самой отрисовки.
            val localMode = MarsWidgetIntents.prefersLocal(context, state)

            // Блок диалога: есть сессия — открываем ИМЕННО её (в её родном чате: локальном
            // или серверном); нет — новый чат с клавиатурой в активном режиме.
            // Ведение в несуществующую сессию было бы худшим ответом на тап.
            val dialogIntent = MarsWidgetIntents.dialogIntent(context, state, localMode)
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
            // Пилюля модели: тап ведёт в список диалогов того режима, который она называет —
            // это ближайшее к «где я и что у меня включено», что можно открыть одним тапом.
            views.setOnClickPendingIntent(
                R.id.widget_mars_model_badge,
                MarsWidgetIntents.activityPending(
                    context,
                    REQUEST_MODEL_BADGE,
                    MarsWidgetIntents.sessionListIntent(context),
                ),
            )
            views.setOnClickPendingIntent(
                R.id.widget_mars_input,
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
                R.id.widget_mars_mic,
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
                    MarsWidgetIntents.activeChatIntent(
                        context,
                        MarsWidgetActionsPolicy.ACTION_NEW_CHAT,
                        state,
                        localMode,
                    ),
                ),
            )

            // Нижняя полоса: разделы в один тап, без промежуточного «открыть приложение».
            views.setOnClickPendingIntent(
                R.id.widget_mars_chip_local,
                MarsWidgetIntents.activityPending(
                    context,
                    REQUEST_CHIP_LOCAL,
                    MarsWidgetIntents.localChatIntent(context, state.sessionId.takeIf { localMode }),
                ),
            )
            views.setOnClickPendingIntent(
                R.id.widget_mars_chip_tasks,
                MarsWidgetIntents.activityPending(
                    context,
                    REQUEST_CHIP_TASKS,
                    MarsWidgetIntents.tasksIntent(context),
                ),
            )
            views.setOnClickPendingIntent(
                R.id.widget_mars_chip_memory,
                MarsWidgetIntents.activityPending(
                    context,
                    REQUEST_CHIP_MEMORY,
                    MarsWidgetIntents.memoryIntent(context, localMode),
                ),
            )
            views.setOnClickPendingIntent(
                R.id.widget_mars_chip_music,
                MarsWidgetIntents.activityPending(
                    context,
                    REQUEST_CHIP_MUSIC,
                    MarsWidgetIntents.musicIntent(context),
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
 *
 * Здесь же живёт решение «куда вести тап» ([activeChatIntent]): серверный чат или локальный.
 * Решение принимается по состоянию виджета и по фактической настройке приложения, а не по
 * тому, какой экран виджет открывал в прошлый раз.
 */
internal object MarsWidgetIntents {

    /**
     * Флаги навигации с домашнего экрана.
     *
     * NEW_TASK обязателен (старт не из Activity), CLEAR_TOP|SINGLE_TOP — чтобы уже
     * открытый чат получил onNewIntent и применил действие, а не открылся второй копией
     * поверх первого.
     */
    private val NAV_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_CLEAR_TOP or
        Intent.FLAG_ACTIVITY_SINGLE_TOP

    /**
     * Интент уровня диалога с действием виджета (серверный чат).
     *
     * Оставлен публичным и без состояния намеренно: это база для [openSessionIntent] и
     * точка совместимости — контрактный тест проверяет именно её, а вся маршрутизация
     * живёт в [activeChatIntent].
     */
    fun chatIntent(context: Context, action: String?): Intent =
        Intent(context, ChatActivity::class.java)
            .addFlags(NAV_FLAGS)
            .also { intent ->
                if (action != null) {
                    intent.putExtra(MarsWidgetActionsPolicy.EXTRA_WIDGET_ACTION, action)
                }
            }

    /**
     * Интент уровня диалога в ЛОКАЛЬНОМ чате (прямой провайдер, без сервера).
     *
     * [sessionId] — конкретный локальный диалог; `null` — новый. [action] — то же значение
     * extra, что читает серверный чат: `LocalChatActivity` применяет его тем же
     * `applyWidgetAction` (клавиатура / диктовка / чистый диалог).
     */
    fun localChatIntent(context: Context, sessionId: String? = null, action: String? = null): Intent =
        LocalChatActivity.newIntent(context, sessionId)
            .addFlags(NAV_FLAGS)
            .also { intent ->
                if (action != null) {
                    intent.putExtra(MarsWidgetActionsPolicy.EXTRA_WIDGET_ACTION, action)
                }
            }

    /**
     * Куда ведёт тап «продолжить разговор» — ГЛАВНАЯ функция этой волны.
     *
     * Порядок решений:
     * 1. `state.isLocal` — последний разговор шёл через прямой провайдер → локальный чат,
     *    ровно та сессия, что на карточке.
     * 2. Серверного device-credential нет, а профиль прямого провайдера настроен → локальный
     *    чат: серверный экран в этом состоянии уводит на ввод пароля и теряет действие.
     * 3. Иначе — серверный чат с той же сессией.
     *
     * `new_chat` намеренно не тащит `sessionId`: «новый чат» с открытой старой сессией —
     * это не новый чат.
     */
    fun activeChatIntent(
        context: Context,
        action: String?,
        state: MarsWidgetActionsPolicy.MarsWidgetState,
    ): Intent = activeChatIntent(context, action, state, prefersLocal(context, state))

    /**
     * Та же маршрутизация с уже принятым решением [local].
     *
     * Нужна, потому что [prefersLocal] читает Keystore и prefs провайдера: в одной сборке
     * RemoteViews таких тапов четыре, и проверять credential четыре раза подряд незачем.
     */
    fun activeChatIntent(
        context: Context,
        action: String?,
        state: MarsWidgetActionsPolicy.MarsWidgetState,
        local: Boolean,
    ): Intent {
        val sessionId = state.sessionId.takeIf { action != MarsWidgetActionsPolicy.ACTION_NEW_CHAT }
        return if (local) {
            localChatIntent(context, sessionId, action)
        } else {
            serverChatIntent(context, action, sessionId)
        }
    }

    /** Тап по карточке последнего диалога: именно он, в его родном чате. */
    fun dialogIntent(context: Context, state: MarsWidgetActionsPolicy.MarsWidgetState): Intent =
        dialogIntent(context, state, prefersLocal(context, state))

    /** Тап по карточке с уже принятым решением о режиме (см. [activeChatIntent]). */
    fun dialogIntent(
        context: Context,
        state: MarsWidgetActionsPolicy.MarsWidgetState,
        local: Boolean,
    ): Intent =
        state.sessionId?.let { sessionId ->
            if (local) {
                localChatIntent(context, sessionId)
            } else {
                openSessionIntent(context, sessionId)
            }
        } ?: activeChatIntent(context, MarsWidgetActionsPolicy.ACTION_COMPOSE, state, local)

    /**
     * Локальный режим? Состояние виджета — первый и главный источник; настройки приложения
     * нужны только чтобы не вести в серверный экран человека, который сервером не пользуется.
     *
     * Оба обращения к хранилищам обёрнуты в `runCatching`: это процесс лаунчера, и падение
     * Keystore (после сброса блокировки экрана, например) не должно ронять виджет — лучше
     * открыть серверный чат, который сам разберётся с авторизацией.
     */
    fun prefersLocal(context: Context, state: MarsWidgetActionsPolicy.MarsWidgetState): Boolean {
        if (state.isLocal) return true
        return !hasServerCredential(context) && hasLocalProvider(context)
    }

    /** Серверный device-credential сохранён (человек прошёл пару или вход). */
    private fun hasServerCredential(context: Context): Boolean =
        runCatching { AndroidKeyStoreCredentialStore(context).read() != null }.getOrDefault(false)

    /** Профиль прямого провайдера настроен и активен. */
    private fun hasLocalProvider(context: Context): Boolean =
        runCatching { LocalProviderPrefsStore(context).state().active != null }.getOrDefault(false)

    /** Серверный чат с необязательной сессией: база [chatIntent] плюс адресат. */
    private fun serverChatIntent(context: Context, action: String?, sessionId: String?): Intent =
        chatIntent(context, action).also { intent ->
            if (sessionId != null) {
                intent.putExtra(ChatActivity.EXTRA_OPEN_SESSION_ID, sessionId)
            }
        }

    /** Открыть конкретный диалог (тап по блоку последнего чата). */
    fun openSessionIntent(context: Context, sessionId: String): Intent =
        chatIntent(context, null).putExtra(ChatActivity.EXTRA_OPEN_SESSION_ID, sessionId)

    /** Уровень списка диалогов — корень раздела CHAT. */
    fun sessionListIntent(context: Context): Intent =
        Intent(context, ChatSessionsActivity::class.java)
            .addFlags(NAV_FLAGS)

    /** Задачи и дела: список TodosActivity, без промежуточных экранов. */
    fun tasksIntent(context: Context): Intent =
        Intent(context, TodosActivity::class.java)
            .addFlags(NAV_FLAGS)

    /**
     * Память владельца: в локальном режиме — локальное хранилище фактов, иначе серверное.
     * Разные экраны читают разные источники, и открыть не тот — значит показать пустой список.
     */
    fun memoryIntent(context: Context, isLocal: Boolean): Intent =
        (if (isLocal) LocalMemoryActivity.newIntent(context) else Intent(context, MemoryActivity::class.java))
            .addFlags(NAV_FLAGS)

    /** Плеер Марса: музыка продолжает играть, экран открывается поверх. */
    fun musicIntent(context: Context): Intent =
        Intent(context, MusicActivity::class.java)
            .addFlags(NAV_FLAGS)

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
            .addFlags(NAV_FLAGS)

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
