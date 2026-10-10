package ru.mars.network.export

import ru.mars.network.model.SessionDetail
import ru.mars.network.model.SessionItem
import ru.mars.network.model.SessionMessage

/** Семантическая роль сообщения транскрипта для экспорта (vocab сервера шире — сводим сюда). */
enum class ExportRole { USER, ASSISTANT, TOOL, THOUGHT, SYSTEM, UNKNOWN }

/**
 * Что включать в Markdown-экспорт сессии. По умолчанию экспортируется полный
 * транскрипт; мысли/инструменты отключаются для «чистого» диалога человек-агент.
 */
data class SessionExportOptions(
    val includeMetadata: Boolean = true,
    val includeTimestamps: Boolean = true,
    val includeTools: Boolean = true,
    val includeThoughts: Boolean = true,
)

/**
 * Чистая политика экспорта сессий: классификация ролей и построение безопасного
 * имени файла. Без Android-зависимостей и IO — полностью покрыта JVM unit-тестами;
 * форматирование документа — в [SessionMarkdownExporter].
 */
object SessionExportPolicy {

    /** Потолок длины имени файла (без расширения). */
    const val MAX_FILE_NAME_CHARS = 80
    private const val MAX_TITLE_IN_FILE_NAME = 48
    private const val ID_SUFFIX_CHARS = 8

    /**
     * Классификация роли сообщения. Словарь сервера/Hermes шире базового
     * user/assistant: tool-события приходят как `tool`/`tool_call`/`function`,
     * мысли — как `thought`/`thinking`/`reasoning` (см. StreamEventMapper).
     * Неизвестная роль не теряется — [ExportRole.UNKNOWN] с исходным текстом.
     */
    fun roleOf(role: String): ExportRole = when (role.trim().lowercase()) {
        "user", "human" -> ExportRole.USER
        "assistant", "agent", "bot" -> ExportRole.ASSISTANT
        "system" -> ExportRole.SYSTEM
        "tool", "tool_call", "tool_result", "function", "function_call" -> ExportRole.TOOL
        "thought", "thinking", "reasoning" -> ExportRole.THOUGHT
        else -> ExportRole.UNKNOWN
    }

    /** Человекочитаемая подпись роли для заголовка секции сообщения. */
    fun labelFor(kind: ExportRole, rawRole: String): String = when (kind) {
        ExportRole.USER -> "Пользователь"
        ExportRole.ASSISTANT -> "Ассистент"
        ExportRole.TOOL -> "Инструмент"
        ExportRole.THOUGHT -> "Мысль"
        ExportRole.SYSTEM -> "Система"
        ExportRole.UNKNOWN -> rawRole.trim().ifBlank { "Неизвестная роль" }
    }

    /** Эмодзи-маркер роли для заголовка секции. */
    fun emojiFor(kind: ExportRole): String = when (kind) {
        ExportRole.USER -> "\uD83D\uDC64"   // 👤
        ExportRole.ASSISTANT -> "\uD83E\uDD16" // 🤖
        ExportRole.TOOL -> "\uD83D\uDEE0\uFE0F"  // 🛠️
        ExportRole.THOUGHT -> "\uD83D\uDCAD" // 💭
        ExportRole.SYSTEM -> "⚙️"
        ExportRole.UNKNOWN -> "❓"
    }

    /**
     * Безопасное имя файла экспорта: `<очищенный заголовок>-<первые 8 симв. id>.md`;
     * без заголовка — `session-<id8>.md`. Из имени убираются разделители путей,
     * служебные символы и всё, что ломает файловые системы (Windows/Unix/SAF).
     */
    fun fileNameFor(id: String, title: String?): String {
        val idPart = sanitizeFileNamePart(id).take(ID_SUFFIX_CHARS).ifBlank { "unknown" }
        val titlePart = sanitizeFileNamePart(title.orEmpty()).take(MAX_TITLE_IN_FILE_NAME).trim('-')
        val base = if (titlePart.isBlank()) "session-$idPart" else "$titlePart-$idPart"
        return base.take(MAX_FILE_NAME_CHARS) + ".md"
    }

    /** Пробелы → `-`, остаются буквы/цифры (любой алфавит), `_`, `.`, `-`; прочее удаляется. */
    fun sanitizeFileNamePart(raw: String): String = raw
        .replace(Regex("\\s+"), "-")
        .replace(Regex("[^\\p{L}\\p{N}_.\\-]"), "")
        .replace(Regex("-{2,}"), "-")
        .trim('-')
}

/**
 * Экспорт транскрипта сессии в чистый читаемый Markdown (share/«Экспорт диалога»).
 *
 * Формат:
 * - `# <заголовок>` + блок метаданных (id, источник, даты, счётчик) и отметка
 *   субагент-сессии с родителем;
 * - каждое сообщение — секция `## <эмодзи> <роль>[ · <время>]`;
 * - пользователь/ассистент — содержимое как есть (это уже Markdown),
 * - инструменты — fenced code block (JSON/вывод команд не должен ломать разметку;
 *  _fence удлиняется, если в контенте есть собственные обратные кавычки),
 * - мысли и системные сообщения — blockquote (визуально «второй план»);
 * - пустое содержимое — плейсхолдер `_пусто_`, пустой транскрипт — `_В сессии нет сообщений._`.
 *
 * Класс чистый (без Android/IO): строку документа пишет на диск/в share-sheet вызывающий код.
 * Контент нормализуется: CRLF → LF, управляющие символы (кроме \n и \t) → пробел.
 */
class SessionMarkdownExporter(
    private val options: SessionExportOptions = SessionExportOptions(),
) {

    /** Экспорт полного ответа детального эндпоинта. */
    fun export(detail: SessionDetail): String = export(detail.toSessionItem(), detail.messages)

    /**
     * Экспорт транскрипта. [session] может быть null (например, экспорт из кэша
     * сообщений без метаданных списка) — документ остаётся валидным.
     */
    fun export(session: SessionItem?, messages: List<SessionMessage>): String {
        val sb = StringBuilder()
        sb.append("# ").append(headingTitle(session)).append('\n').append('\n')
        if (session != null && options.includeMetadata) appendMetadata(sb, session, messages.size)
        if (session?.isSubagent == true) appendSubagentNote(sb, session)
        sb.append("---").append('\n').append('\n')

        val visible = messages.filter { isVisible(it) }
        if (visible.isEmpty()) {
            sb.append("_В сессии нет сообщений._").append('\n')
        } else {
            visible.forEachIndexed { index, message ->
                if (index > 0) sb.append('\n')
                appendMessage(sb, message)
            }
        }
        return sb.toString().trimEnd('\n') + "\n"
    }

    private fun SessionDetail.toSessionItem(): SessionItem = SessionItem(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        messageCount = messages.size,
    )

    private fun isVisible(message: SessionMessage): Boolean =
        when (SessionExportPolicy.roleOf(message.role)) {
            ExportRole.TOOL -> options.includeTools
            ExportRole.THOUGHT -> options.includeThoughts
            else -> true
        }

    private fun headingTitle(session: SessionItem?): String = when {
        session == null -> "Экспорт диалога"
        !session.title.isNullOrBlank() -> flatten(session.title)
        session.id.isNotBlank() -> "Сессия ${session.id}"
        else -> "Сессия без названия"
    }

    private fun appendMetadata(sb: StringBuilder, session: SessionItem, transcriptSize: Int) {
        sb.append("- **ID:** `").append(session.id).append('`').append('\n')
        val source = session.sourceLabel ?: session.sourceTag ?: session.sessionSource
        if (!source.isNullOrBlank()) {
            sb.append("- **Источник:** ").append(flatten(source)).append('\n')
        }
        if (!session.createdAt.isNullOrBlank()) {
            sb.append("- **Создана:** ").append(flatten(session.createdAt)).append('\n')
        }
        if (!session.updatedAt.isNullOrBlank()) {
            sb.append("- **Обновлена:** ").append(flatten(session.updatedAt)).append('\n')
        }
        sb.append("- **Сообщений в транскрипте:** ").append(transcriptSize).append('\n')
        sb.append('\n')
    }

    private fun appendSubagentNote(sb: StringBuilder, session: SessionItem) {
        sb.append("> \uD83E\uDDE9 **Сессия субагента**")
        if (!session.parentSessionId.isNullOrBlank()) {
            sb.append(" · родитель: `").append(session.parentSessionId).append('`')
        }
        if (session.readOnly) sb.append(" · только чтение")
        sb.append('\n').append('\n')
    }

    private fun appendMessage(sb: StringBuilder, message: SessionMessage) {
        val kind = SessionExportPolicy.roleOf(message.role)
        sb.append("## ")
            .append(SessionExportPolicy.emojiFor(kind))
            .append(' ')
            .append(SessionExportPolicy.labelFor(kind, message.role))
        if (options.includeTimestamps && !message.createdAt.isNullOrBlank()) {
            sb.append(" · ").append(flatten(message.createdAt))
        }
        sb.append('\n').append('\n')
        when (kind) {
            ExportRole.TOOL -> sb.append(codeBlock(sanitize(message.content))).append('\n')
            ExportRole.THOUGHT, ExportRole.SYSTEM ->
                sb.append(blockquote(sanitize(message.content))).append('\n')
            else -> sb.append(sanitize(message.content).ifBlank { EMPTY_PLACEHOLDER }).append('\n')
        }
    }

    /** Fenced code block; fence длиннее самой длинной серии бэктиков в контенте. */
    private fun codeBlock(content: String): String {
        val longest = Regex("`+").findAll(content).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, longest + 1))
        val body = content.ifBlank { EMPTY_PLACEHOLDER }
        return fence + "text\n" + body + "\n" + fence
    }

    /** Цитата: каждая строка с префиксом `> `; пустые строки — просто `>`. */
    private fun blockquote(content: String): String =
        content.ifBlank { EMPTY_PLACEHOLDER }
            .split('\n')
            .joinToString("\n") { if (it.isBlank()) ">" else "> $it" }

    /** CRLF/CR → LF, управляющие символы (кроме перевода строки и табуляции) → пробел. */
    private fun sanitize(text: String): String = text
        .replace("\r\n", "\n")
        .map { ch ->
            when {
                ch == '\r' -> '\n'
                ch == '\n' || ch == '\t' -> ch
                ch.isISOControl() -> ' '
                else -> ch
            }
        }
        .joinToString("")
        .trim()

    /** Однострочное представление метаданных (переносы схлопываются в пробел). */
    private fun flatten(text: String): String =
        sanitize(text).replace('\n', ' ').replace(Regex(" {2,}"), " ").trim()

    private companion object {
        const val EMPTY_PLACEHOLDER = "_пусто_"
    }
}
