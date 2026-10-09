package ru.mars.mobile

/** Действия над сообщением чата (паритет с message actions WebUI). */
enum class MessageActionKind(val label: String) {
    COPY("Копировать текст"),
    QUOTE("Цитировать"),
    SHARE("Поделиться"),
}

/**
 * Результат вставки цитаты в композер: новый текст поля ввода и позиция курсора.
 * Курсор всегда стоит сразу за блоком цитаты — там пользователь продолжает ввод.
 */
data class ComposerQuote(val text: String, val cursor: Int)

/**
 * Чистая политика действий над сообщением: какие действия доступны, как строится
 * цитата (`> …` + пустая строка, многострочный текст цитируется построчно),
 * как цитата соединяется с текущим черновиком композера и что уходит в шаринг.
 * Без Android-импортов — тестируется на JVM.
 */
object MessageActionPolicy {
    /** Цитата длиннее этого числа строк усекается (с маркером «…»). */
    const val QUOTE_MAX_LINES = 8

    /** Жёсткий предел длины цитаты в символах, чтобы не раздувать композер. */
    const val QUOTE_MAX_CHARS = 500

    /** Тост-подтверждение копирования (короткий, без точки — как в WebUI). */
    const val COPY_TOAST = "Скопировано"

    /** Тема письма/карточки при шаринге сообщения. */
    const val SHARE_SUBJECT = "Сообщение из Марса"

    /** Доступные действия: пустое сообщение нечего копировать/цитировать/шарить. */
    fun actionsFor(messageText: String): List<MessageActionKind> =
        if (messageText.isBlank()) emptyList() else MessageActionKind.values().toList()

    /**
     * Отрывок сообщения для цитирования: trimmed, не длиннее [maxLines] строк
     * и [maxChars] символов; усечение помечается «…».
     */
    fun quoteExcerpt(
        text: String,
        maxLines: Int = QUOTE_MAX_LINES,
        maxChars: Int = QUOTE_MAX_CHARS,
    ): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val lines = trimmed.lines()
        var excerpt = lines.take(maxLines).joinToString("\n")
        var truncated = lines.size > maxLines
        if (excerpt.length > maxChars) {
            excerpt = excerpt.take(maxChars).trimEnd()
            truncated = true
        }
        return if (truncated) "$excerpt…" else excerpt
    }

    /**
     * Markdown-цитата: каждая строка получает префикс `> `, в конце пустая строка —
     * одиночная строка превращается ровно в `> текст\n\n`. Пустой ввод -> пустой блок.
     */
    fun formatQuote(quotedText: String): String {
        if (quotedText.isBlank()) return ""
        return quotedText.lines().joinToString("\n") { line ->
            if (line.isBlank()) ">" else "> $line"
        } + "\n\n"
    }

    /** Готовый блок цитаты из текста сообщения (отрывок + форматирование). */
    fun quoteBlock(messageText: String): String = formatQuote(quoteExcerpt(messageText))

    /**
     * Соединяет блок цитаты с текущим текстом композера: цитата всегда сверху,
     * курсор — сразу за ней. Пустой черновик -> просто цитата.
     */
    fun composeQuote(currentComposerText: String, messageText: String): ComposerQuote {
        val block = quoteBlock(messageText)
        if (block.isEmpty()) return ComposerQuote(currentComposerText, currentComposerText.length)
        return if (currentComposerText.isBlank()) {
            ComposerQuote(block, block.length)
        } else {
            ComposerQuote(block + currentComposerText, block.length)
        }
    }

    /** Текст для ACTION_SEND: сообщение уходит дословно, без роли и украшений. */
    fun shareBody(messageText: String): String = messageText
}
