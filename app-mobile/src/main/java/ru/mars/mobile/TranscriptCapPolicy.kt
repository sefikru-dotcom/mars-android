package ru.mars.mobile

/**
 * Ограничение роста транскрипта — чистая JVM-политика (без Android), тесты:
 * TranscriptCapPolicyTest.
 *
 * Проблема: `ChatUiState.messages` рос вечно (сотни сообщений после reload'ов и
 * длинных сессий), а каждое сообщение могло нести мегабайты текста — и состояние,
 * и (раньше) полный пересбор всех карточек на каждый кадр стрима масштабируются
 * от этого линейно → ANR/OOM.
 *
 * Два независимых лимита, оба с якорем «старое подрезано» (свежий хвост не теряет
 * ничего — режется только ГОЛОВА):
 * - [MAX_MESSAGES] — cap на число сообщений: применяется в `ChatUiState.bindSession`
 *   к истории с сервера; сверх лимита в начало вставляется ОДНО якорное сообщение
 *   [ChatMessage.isTrimAnchor] со счётчиком скрытых (рендерится приглушённой строкой);
 * - [MAX_BUBBLE_CHARS] — cap на текст одного пузыря: история режется здесь
 *   ([capMessages]), живой стрим — штатным `StreamBufferGuard` (его скользящее окно
 *   с маркером «вывод обрезан» — тот же якорный механизм; ChatUiState конструирует
 *   guard с этим лимитом).
 *
 * Идемпотентность: повторное применение к уже подрезанному списку/тексту не режет
 * дальше и не удваивает якоря (проверено тестами) — capMessages безопасно звать
 * на каждый bindSession.
 */
object TranscriptCapPolicy {

    /** Максимум сообщений в состоянии: хвост сохраняется, голова заменяется якорем. */
    const val MAX_MESSAGES = 300

    /**
     * Лимит текста ОДНОГО пузыря (символы). 96K — с большим запасом покрывает любой
     * реальный ответ (~24K слов), но не даёт одному сообщению занять сотни КБ и
     * парситься секундами. Строже дефолта StreamBufferGuard (256K) в 2.7 раза.
     */
    const val MAX_BUBBLE_CHARS = 96 * 1024

    /** Якорь подрезанного пузыря: ставится в голову, хвост (свежий текст) сохраняется. */
    const val BUBBLE_TRIM_ANCHOR = "… (начало сообщения подрезано)"

    /** Подпись якорной карточки списка сообщений. */
    fun anchorLabel(trimmedCount: Int): String =
        "Старые сообщения подрезаны — скрыто: $trimmedCount"

    /**
     * Cap текста пузыря: [MAX_BUBBLE_CHARS] и меньше проходит как есть; длиннее —
     * сохраняется КОНЕЦ (последние выводы важнее начала), голова заменяется якорем.
     * Идемпотентно: capBubbleText(capBubbleText(x)) == capBubbleText(x).
     */
    fun capBubbleText(text: String): String {
        if (text.length <= MAX_BUBBLE_CHARS) return text
        return BUBBLE_TRIM_ANCHOR + "\n" + text.takeLast(MAX_BUBBLE_CHARS)
    }

    /**
     * Cap списка сообщений + текстов каждого пузыря. Хвост из [MAX_MESSAGES] последних
     * сообщений сохраняется полностью; если сверху уже лежит якорь (повторный cap),
     * его счётчик поглощается новым. Возвращает список не длиннее MAX_MESSAGES + 1.
     */
    fun capMessages(messages: List<ChatMessage>): List<ChatMessage> {
        val previousAnchor = messages.firstOrNull()?.takeIf { it.isTrimAnchor }
        val body = if (previousAnchor != null) messages.drop(1) else messages
        val capped = body.map {
            if (it.text.length > MAX_BUBBLE_CHARS) it.copy(text = capBubbleText(it.text)) else it
        }
        if (capped.size <= MAX_MESSAGES) {
            return if (previousAnchor != null) listOf(previousAnchor) + capped else capped
        }
        val trimmed = capped.size - MAX_MESSAGES
        val anchor = ChatMessage(
            role = "assistant",
            text = "",
            timestamp = capped.first().timestamp,
            isTrimAnchor = true,
            trimmedCount = trimmed + (previousAnchor?.trimmedCount ?: 0),
        )
        return listOf(anchor) + capped.takeLast(MAX_MESSAGES)
    }
}
