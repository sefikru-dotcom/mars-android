package ru.mars.mobile

/**
 * Операция над ходом или сессией, которая выполняется СЕРВЕРОМ (`POST /api/session/...`).
 *
 * Это не то же самое, что [TurnRegeneratePolicy] — та пересобирает последний ответ
 * локально, не трогая историю на сервере. Здесь каждый пункт меняет состояние диалога
 * по ту сторону и потому требует перезагрузки транскрипта.
 */
enum class TurnOpKind(val label: String) {
    /** `POST /api/session/undo` — снять последнюю пару «запрос-ответ». */
    UNDO("Отменить последний ход"),

    /** `POST /api/session/retry` — сервер сам удаляет последний ответ и повторяет запрос. */
    RETRY("Повторить ход на сервере"),

    /** `POST /api/session/branch` — форк сессии от указанного числа сообщений. */
    BRANCH("Ответвить от этого хода"),

    /** `POST /api/session/truncate` — обрезать историю до N сообщений. */
    TRUNCATE("Откатить до этого хода"),

    /** `POST /api/session/duplicate` — полная копия сессии. */
    DUPLICATE("Создать копию сессии"),

    /** `POST /api/session/clear` — обнулить историю. */
    CLEAR("Очистить историю сессии"),

    /** `POST /api/session/title/regenerate` — перегенерировать название. */
    REGENERATE_TITLE("Обновить название"),
    ;

    /** Разрушительные операции обязаны проходить через диалог подтверждения. */
    val destructive: Boolean
        get() = this == TRUNCATE || this == CLEAR || this == UNDO

    /** Операции, создающие новую сессию: после успеха надо переключиться на неё. */
    val spawnsSession: Boolean
        get() = this == BRANCH || this == DUPLICATE
}

/**
 * Доступность операций над ходом на конкретном сообщении.
 *
 * Правила сняты с боевого WebUI и повторяют его ограничения:
 * - операции недоступны в read-only (импорт) и в субагентской сессии — сервер отвечает
 *   400 «Subagent sessions are view-only…»;
 * - `truncate`/`branch` осмысленны только для сообщения, за которым есть что оставить:
 *   откат «до нуля» — это [TurnOpKind.CLEAR], а не откат хода;
 * - `retry` — только на последнем ответе ассистента: сервер работает с хвостом.
 */
object TurnOpsPolicy {
    /**
     * Доступные операции над сообщением с индексом [messageIndex] в транскрипте
     * длины [messageCount]. Возвращает список в порядке показа в меню.
     */
    fun messageOps(
        messageIndex: Int,
        messageCount: Int,
        role: String,
        readOnly: Boolean,
        subagent: Boolean,
    ): List<TurnOpKind> {
        if (readOnly || subagent) return emptyList()
        if (messageIndex < 0 || messageIndex >= messageCount) return emptyList()
        val isLast = messageIndex == messageCount - 1
        val ops = mutableListOf<TurnOpKind>()
        if (isLast && role == "assistant") ops += TurnOpKind.RETRY
        // Откат оставляет сообщения ДО выбранного: индекс 0 откатил бы пустую историю.
        if (messageIndex > 0) {
            ops += TurnOpKind.TRUNCATE
            ops += TurnOpKind.BRANCH
        }
        return ops
    }

    /**
     * Сколько сообщений оставить при обрезке до хода с индексом [messageIndex]:
     * само выбранное сообщение НЕ сохраняется — пользователь откатывает его.
     */
    fun keepCountForTruncate(messageIndex: Int): Int = messageIndex.coerceAtLeast(0)

    /**
     * Доступные операции над всей сессией. `undo` требует непустой истории: на пустой
     * сессии сервер отвечает `error="Nothing to undo."`, и пункт меню был бы обманом.
     */
    fun sessionOps(messageCount: Int, readOnly: Boolean, subagent: Boolean): List<TurnOpKind> {
        if (readOnly || subagent) return emptyList()
        val ops = mutableListOf<TurnOpKind>()
        if (messageCount > 0) ops += TurnOpKind.UNDO
        if (messageCount > 0) ops += TurnOpKind.REGENERATE_TITLE
        ops += TurnOpKind.DUPLICATE
        if (messageCount > 0) ops += TurnOpKind.CLEAR
        return ops
    }

    /** Нужен ли диалог подтверждения перед выполнением [kind]. */
    fun needsConfirmation(kind: TurnOpKind): Boolean = kind.destructive
}

/**
 * Тексты подтверждений и результат-сообщения для серверных операций над ходом.
 * Без Android-импортов — тестируется на JVM.
 */
object TurnOpsPresenter {
    const val TITLE = "Операции с сессией"

    /** Заголовок диалога подтверждения — по виду операции. */
    fun confirmTitle(kind: TurnOpKind): String = when (kind) {
        TurnOpKind.TRUNCATE -> "Откатить историю?"
        TurnOpKind.CLEAR -> "Очистить историю?"
        TurnOpKind.UNDO -> "Отменить последний ход?"
        else -> "Подтвердите операцию"
    }

    /**
     * Тело подтверждения. Для обрезки явно называется число остающихся сообщений —
     * «продолжить?» без цифры не даёт понять, сколько истории уцелеет.
     */
    fun confirmBody(kind: TurnOpKind, keepCount: Int = 0): String = when (kind) {
        TurnOpKind.TRUNCATE ->
            "Останется $keepCount ${messageWord(keepCount)}. Остальное будет удалено безвозвратно."
        TurnOpKind.CLEAR -> "Вся история диалога будет удалена безвозвратно."
        TurnOpKind.UNDO -> "Последняя пара «запрос-ответ» будет снята с сервера."
        else -> "Операция изменит диалог на сервере."
    }

    /** Текст успеха: серверных полей может не быть, поэтому формулировка общая и честная. */
    fun successMessage(kind: TurnOpKind, removedCount: Int = 0): String = when (kind) {
        TurnOpKind.UNDO ->
            if (removedCount > 0) "Отменено сообщений: $removedCount" else "Последний ход отменён"
        TurnOpKind.RETRY -> "Ход повторяется"
        TurnOpKind.TRUNCATE -> "История откатана"
        TurnOpKind.CLEAR -> "История очищена"
        TurnOpKind.DUPLICATE -> "Копия сессии создана"
        TurnOpKind.BRANCH -> "Ветка создана"
        TurnOpKind.REGENERATE_TITLE -> "Название обновлено"
    }

    /**
     * Отказ сервера приходит в поле `error` готовым текстом («Nothing to undo.»).
     * Показываем его дословно: это состояние сервера, а не догадка клиента.
     */
    fun refusalMessage(serverError: String?): String =
        serverError?.trim().takeUnless { it.isNullOrEmpty() } ?: "Операция не выполнена"

    /** Русская форма слова «сообщение» для числа [n]. */
    fun messageWord(n: Int): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            mod10 == 1 && mod100 != 11 -> "сообщение"
            mod10 in 2..4 && mod100 !in 12..14 -> "сообщения"
            else -> "сообщений"
        }
    }
}
