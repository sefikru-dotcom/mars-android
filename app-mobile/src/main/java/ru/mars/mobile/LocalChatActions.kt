package ru.mars.mobile

import ru.mars.network.local.LocalRole

/**
 * Действие над сообщением в ленте локального диалога.
 *
 * Набор намеренно узкий: это то, что умеет делать сама лента без сервера. «Поделиться»
 * сюда не входит — оно требует `Intent` и живёт на экране (W4).
 */
enum class LocalChatAction { COPY, RETRY, EDIT, SPEAK, CONTINUE }

/**
 * Чистые правила «какое действие доступно для строки ленты» и русские подписи.
 *
 * Зачем отдельный файл: экран (W4) не должен сам решать, можно ли повторить ответ или
 * озвучить карточку инструмента. Иначе правила расползутся по обработчикам нажатий, а
 * тесты на них появятся только вместе с эмулятором. Здесь ни одного импорта `android.*`
 * — всё проверяется на JVM.
 *
 * Что НЕ делает политика: не копирует в буфер, не повторяет запрос, не правит историю.
 * Это делает экран; политика лишь отвечает «можно?» и «как назвать для скринридера».
 */
object LocalChatActions {

    /** Подпись пункта меню. */
    fun label(action: LocalChatAction): String = when (action) {
        LocalChatAction.COPY -> "Копировать"
        LocalChatAction.RETRY -> "Повторить"
        LocalChatAction.EDIT -> "Изменить"
        LocalChatAction.SPEAK -> "Озвучить"
        LocalChatAction.CONTINUE -> "Продолжить"
    }

    /**
     * Есть ли что копировать.
     *
     * Копируется текст сообщения, а у карточки инструмента — аргументы вызова: это
     * единственное её содержимое, которое человек видит и может захотеть перенести.
     */
    fun canCopy(item: LocalChatItem): Boolean =
        item.text.isNotBlank() || item.tool != null

    /**
     * Можно ли повторить ход по этой строке.
     *
     * Только последний ответ ассистента и только когда ход не идёт: повтор середины
     * диалога отправил бы провайдеру историю, которой уже нет, а повтор во время стрима
     * дал бы два параллельных ответа в одной ленте.
     */
    fun canRetry(item: LocalChatItem, isLastAssistant: Boolean): Boolean =
        isLastAssistant &&
            item.role == LocalRole.ASSISTANT &&
            item.tool == null &&
            !item.streaming &&
            item.text.isNotBlank()

    /** Можно ли править: только своё сообщение, целиком отправленное. */
    fun canEdit(item: LocalChatItem): Boolean =
        item.role == LocalRole.USER &&
            item.tool == null &&
            !item.streaming &&
            item.text.isNotBlank()

    /**
     * Можно ли озвучить: только готовый ответ ассистента.
     *
     * [speechAvailable] — есть ли на устройстве движок синтеза. Без него пункт меню
     * скрывается: мёртвая кнопка «Озвучить», которая молчит, хуже её отсутствия.
     */
    fun canSpeak(item: LocalChatItem, speechAvailable: Boolean = true): Boolean =
        speechAvailable &&
            item.role == LocalRole.ASSISTANT &&
            item.tool == null &&
            !item.streaming &&
            item.text.isNotBlank()

    /**
     * Можно ли продолжить этот ответ.
     *
     * Продолжение осмысленно ровно в одном случае: провайдер упёрся в лимит выходных
     * токенов (`finish_reason = length`), то есть модель не закончила мысль, а её
     * оборвали. Решение принимает [LocalContinuePolicy] — здесь только проброс, чтобы
     * набор действий оставался единственным местом, где решается «что можно со строкой».
     */
    fun canContinue(item: LocalChatItem, isLastAssistant: Boolean): Boolean =
        isLastAssistant &&
            item.role == LocalRole.ASSISTANT &&
            item.tool == null &&
            !item.streaming &&
            LocalContinuePolicy.shouldOffer(
                truncated = item.truncated,
                interrupted = item.interrupted,
                text = item.text,
            )

    /** Текст, который копирует действие [LocalChatAction.COPY]. */
    fun copyText(item: LocalChatItem): String =
        item.text.ifBlank { item.tool?.argumentsJson.orEmpty() }

    /** Идентификатор последнего ответа ассистента — цель для «Повторить»; null — такого нет. */
    fun lastAssistantId(items: List<LocalChatItem>): String? = items
        .lastOrNull { it.role == LocalRole.ASSISTANT && it.tool == null && it.text.isNotBlank() }
        ?.id

    /**
     * Доступные действия для строки в порядке показа.
     *
     * [isLastAssistant] считает вызывающий через [lastAssistantId] (или сам знает по
     * индексу), чтобы политика не пересчитывала список на каждое нажатие.
     */
    fun available(
        item: LocalChatItem,
        isLastAssistant: Boolean,
        speechAvailable: Boolean = true,
    ): List<LocalChatAction> = buildList {
        if (canCopy(item)) add(LocalChatAction.COPY)
        if (canRetry(item, isLastAssistant)) add(LocalChatAction.RETRY)
        if (canEdit(item)) add(LocalChatAction.EDIT)
        if (canSpeak(item, speechAvailable)) add(LocalChatAction.SPEAK)
        if (canContinue(item, isLastAssistant)) add(LocalChatAction.CONTINUE)
    }

    /**
     * Описание для скринридера: что именно произойдёт, а не только «Копировать».
     * Скринридер читает пункт без контекста строки, поэтому роль сообщения озвучивается.
     */
    fun contentDescription(action: LocalChatAction, item: LocalChatItem): String {
        val subject = when {
            item.tool != null -> "вызов инструмента ${item.tool.name}"
            item.role == LocalRole.USER -> "своё сообщение"
            item.role == LocalRole.ASSISTANT -> "ответ ассистента"
            else -> "сообщение"
        }
        return when (action) {
            LocalChatAction.COPY -> "Копировать $subject"
            LocalChatAction.RETRY -> "Повторить последний ответ ассистента"
            LocalChatAction.EDIT -> "Изменить своё сообщение и отправить заново"
            LocalChatAction.SPEAK -> "Озвучить ответ ассистента"
            LocalChatAction.CONTINUE -> "Продолжить обрезанный ответ"
        }
    }
}
