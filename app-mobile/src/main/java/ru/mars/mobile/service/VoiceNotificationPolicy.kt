package ru.mars.mobile.service

/**
 * Чистая политика уведомления голосового сервиса ([MarsVoiceService]).
 *
 * Здесь нет ни одного типа `android.*`: ни ресурсов, ни `PendingIntent`, ни `NotificationCompat`.
 * Поэтому файл проверяется юнит-тестом на JVM, а сервис остаётся единственным местом, где
 * политика превращается в пиксели и в `Intent`.
 *
 * Что решает политика и что нет:
 * - **решает** — какие тексты видит человек в каждом состоянии голосового цикла, какие кнопки
 *   (Стоп, Открыть) показаны, как они подписаны для скринридера и в каком порядке идут в
 *   свёрнутой шторке;
 * - **не решает** — иконки (`R.drawable.*` живут в сервисе), идентификатор канала в системе,
 *   момент остановки сервиса. Политика ничего не останавливает и не запускает: она описывает
 *   состояние, а не управляет им.
 *
 * Состояния повторяют фазы голосового канала волны 2 ([ru.mars.mobile.LocalVoicePhase]) ровно
 * для того, чтобы кнопка на экране и строка в шторке не расходились. Отличие одно: в фоне
 * [Phase.IDLE] означает не «микрофон закрыт, можно начинать», а «микрофон открыт, жду ключевую
 * фразу» — поэтому тексты у [Phase.IDLE] здесь про ожидание, а не про готовность.
 */
object VoiceNotificationPolicy {

    /** Идентификатор канала: постоянный, иначе Android создаст второй канал и настройки потеряются. */
    const val CHANNEL_ID: String = "mars_voice_conversation"

    /** Название канала в системных настройках уведомлений. */
    const val CHANNEL_NAME: String = "Голосовой разговор"

    /** Пояснение к каналу: человек должен понимать, зачем приложению микрофон в фоне. */
    const val CHANNEL_DESCRIPTION: String =
        "Микрофон и озвучка во время разговора при погашенном экране"

    /** Одно уведомление на процесс: второй разговор поверх первого невозможен. */
    const val NOTIFICATION_ID: Int = 0x4D56 // "MV" — Mars Voice

    /** Действие кнопки «Стоп»: завершает разговор и снимает сервис. */
    const val ACTION_STOP: String = "ru.mars.mobile.service.action.VOICE_STOP"

    /** Ответ приложения на распознанную реплику: сервис его озвучивает. */
    const val ACTION_ANSWER: String = "ru.mars.mobile.service.action.VOICE_ANSWER"

    /** Текст ответа в [ACTION_ANSWER]. */
    const val EXTRA_ANSWER: String = "ru.mars.mobile.service.extra.VOICE_ANSWER_TEXT"

    /** Подпись кнопки «Стоп»: одно слово, шторка не любит длинных подписей. */
    const val LABEL_STOP: String = "Стоп"

    /** Подпись кнопки «Открыть»: возвращает человека в приложение. */
    const val LABEL_OPEN: String = "Открыть"

    /**
     * Фаза голосового цикла — то, что видит человек в шторке.
     *
     * [IDLE] — микрофон открыт, идёт поиск ключевой фразы; [LISTENING] — фраза найдена, сервис
     * пишет реплику; [THINKING] — реплика отдана приложению, ответа ещё нет; [SPEAKING] — ответ
     * звучит. Отдельной фазы ошибки нет намеренно: ошибка — это текст ([text] с `detail`), а не
     * состояние, иначе уведомление залипало бы в красном после разового сбоя микрофона.
     */
    enum class Phase {
        /** Ждём ключевую фразу: микрофон уже открыт, но реплики ещё нет. */
        IDLE,

        /** Пишем реплику после ключевой фразы. */
        LISTENING,

        /** Реплика ушла в приложение, ждём ответ провайдера. */
        THINKING,

        /** Ответ озвучивается. */
        SPEAKING,
    }

    /** Вид кнопки в уведомлении; иконку по нему выбирает сервис. */
    enum class ActionType {
        /** Завершить разговор: закрыть микрофон, заглушить TTS, снять сервис. */
        STOP,

        /** Открыть приложение на экране разговора. */
        OPEN,
    }

    /**
     * Одна кнопка уведомления.
     *
     * @param type вид кнопки (по нему сервис выбирает иконку и способ `PendingIntent`);
     * @param action action-строка для `Intent` сервиса; `null` — кнопка не идёт через
     *   [MarsVoiceService.onStartCommand] (кнопка «Открыть» открывает Activity);
     * @param label видимая подпись; её же читает скринридер шторки — отдельного
     *   `contentDescription` у действия уведомления в платформе нет;
     * @param contentDescription развёрнутая формулировка для экранных зеркал состояния
     *   (своя строка статуса в приложении, баннер, подсказка кнопки).
     */
    data class VoiceAction(
        val type: ActionType,
        val action: String?,
        val label: String,
        val contentDescription: String,
    )

    /** Заголовок уведомления: одно-два слова, чтобы строка не обрезалась на замке. */
    fun title(phase: Phase): String = when (phase) {
        Phase.IDLE -> "Марс на связи"
        Phase.LISTENING -> "Слушаю"
        Phase.THINKING -> "Думаю"
        Phase.SPEAKING -> "Говорю"
    }

    /**
     * Текст уведомления — что происходит и что человеку делать.
     *
     * @param phase текущая фаза;
     * @param detail причина, если цикл не пошёл (нет разрешения, микрофон занят, движка нет).
     *   Заданная причина важнее фазы: человеку нужно знать, почему молчит, а не «жду фразу».
     */
    fun text(phase: Phase, detail: String? = null): String {
        val reason = detail?.trim().orEmpty()
        if (reason.isNotEmpty()) return reason
        return when (phase) {
            Phase.IDLE -> "Скажите «Марс, слушай», чтобы спросить"
            Phase.LISTENING -> "Говорите — я запишу реплику и отвечу голосом"
            Phase.THINKING -> "Реплика принята, готовлю ответ…"
            Phase.SPEAKING -> "Ответ звучит. Скажите «Марс, слушай», чтобы продолжить"
        }
    }

    /**
     * Полное описание состояния для скринридера и для экранных зеркал.
     *
     * Шторка читает заголовок и текст уведомления; это описание нужно тем местам, где состояние
     * рисуется своими руками (строка статуса в приложении, баннер поверх замка).
     */
    fun contentDescription(phase: Phase): String = when (phase) {
        Phase.IDLE -> "Голосовой разговор включён. Жду фразу «Марс, слушай»"
        Phase.LISTENING -> "Слушаю реплику"
        Phase.THINKING -> "Думаю над ответом"
        Phase.SPEAKING -> "Говорю ответ"
    }

    /**
     * Кнопки уведомления в порядке шторки: «Стоп» первой — завершить разговор человек должен
     * уметь одной кнопкой и не искать её в раскрытом виде.
     *
     * Состав кнопок от фазы не зависит (Стоп и Открыть нужны всегда), но формулировка для
     * скринридера зависит: во время озвучки «Стоп» — это ещё и «замолчи».
     */
    fun actions(phase: Phase): List<VoiceAction> = listOf(
        VoiceAction(
            type = ActionType.STOP,
            action = ACTION_STOP,
            label = LABEL_STOP,
            contentDescription = when (phase) {
                Phase.SPEAKING -> "Прервать ответ и завершить голосовой разговор"
                Phase.THINKING -> "Отменить ответ и завершить голосовой разговор"
                else -> "Завершить голосовой разговор"
            },
        ),
        VoiceAction(
            type = ActionType.OPEN,
            action = null,
            label = LABEL_OPEN,
            contentDescription = "Открыть Марс на экране разговора",
        ),
    )

    /**
     * Индексы кнопок для свёрнутой шторки. Обе кнопки компактны, поэтому видны обе; список
     * отдаётся массивом, потому что `NotificationCompat` ждёт именно его.
     */
    fun compactActionIndices(phase: Phase): IntArray {
        val count = actions(phase).size
        return if (count == 0) IntArray(0) else IntArray(count) { it }
    }

    /** Вид кнопки по action-строке `Intent`; `null` — строка не про кнопку. */
    fun typeOf(action: String?): ActionType? = when (action) {
        ACTION_STOP -> ActionType.STOP
        else -> null
    }

    /** Пришёл ли `Intent` на остановку разговора. */
    fun isStop(action: String?): Boolean = action == ACTION_STOP

    /** Пришёл ли `Intent` с ответом приложения. */
    fun isAnswer(action: String?): Boolean = action == ACTION_ANSWER

    /**
     * Показывать ли уведомление в фазе [phase]. Сейчас сервис всегда живёт в foreground, поэтому
     * ответ всегда `true`; функция существует, чтобы правило «нет разговора — нет уведомления»
     * было выражено политикой, а не разбросано по сервису.
     */
    fun isAlive(phase: Phase): Boolean = phase in Phase.values()
}
