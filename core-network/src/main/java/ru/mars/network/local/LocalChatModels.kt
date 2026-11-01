package ru.mars.network.local

/**
 * Модель локального диалога.
 *
 * Отличия от серверной модели (`ru.mars.network.model.ChatModels`) намеренные: там
 * сообщение — это то, что показал сервер, здесь — то, что мы отправили провайдеру и
 * что получили в ответ, включая вызовы инструментов. Смешивать нельзя: у сервера
 * свои идентификаторы сессий и свой журнал, у локального чата их нет вовсе.
 */

enum class LocalRole { SYSTEM, USER, ASSISTANT, TOOL }

/** Вызов инструмента, как его вернула модель. */
data class LocalToolCall(val id: String, val name: String, val argumentsJson: String)

/**
 * Расход одного шага, из финального кадра `usage`.
 *
 * Зачем отдельно от [LocalMessage]: расход относится к ходу, а не к сообщению, и нужен
 * пилюле контекста сразу, не дожидаясь сохранения истории. Провайдеры, которые не
 * присылают `usage`, оставляют нули — и пилюля честно скрывается, а не рисует 0%.
 *
 * `promptTokens` здесь — заполнение окна на этом шаге (вся отправленная история),
 * `completionTokens` — только ответ. Складывать по шагам нужно именно `totalTokens`,
 * а заполнение брать из последнего шага: иначе процент контекста удваивался бы.
 */
data class LocalUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
) {
    /** Есть ли что показывать: нули означают «провайдер не сообщил». */
    val isMeasured: Boolean get() = promptTokens > 0 || completionTokens > 0 || totalTokens > 0
}

/**
 * Вид вложения.
 *
 * Набор закрытый и короткий намеренно: [OI]-совместимый провайдер понимает в сообщении
 * ровно два вида содержимого — картинку и текст. Произвольный файл (PDF, DOCX, ZIP)
 * отправить нечем, и притворяться, что отправили, нельзя: сообщение уйдёт без файла,
 * а человек будет ждать ответа по документу, которого модель не видела.
 */
enum class AttachmentKind { IMAGE, TEXT_FILE, DOCUMENT }

/**
 * Вложение сообщения.
 *
 * Данные хранятся в самом сообщении, а не ссылкой на файл: история диалога живёт дольше
 * временного файла и должна переотправляться провайдеру как есть (в том числе после
 * перезапуска), поэтому у картинки — готовый base64, у текстового файла — уже прочитанный
 * текст. `dataBase64` у [AttachmentKind.TEXT_FILE] означает «файл двоичный»: разбор
 * текста — забота вызывающего, у модуля нет ни файловой системы, ни `android.*`.
 *
 * `mimeType` — как его сообщила система выбора файла (`image/jpeg`, `text/plain`); пустая
 * строка допустима и для картинки подставляется `image/jpeg`.
 */
data class LocalAttachment(
    val kind: AttachmentKind,
    val mimeType: String = "",
    val fileName: String? = null,
    val dataBase64: String? = null,
    val textContent: String? = null,
)

/**
 * Сообщение диалога.
 *
 * `toolCalls` заполняется только у [LocalRole.ASSISTANT]; `toolCallId` — только у
 * [LocalRole.TOOL] и указывает, на какой вызов это ответ. Провайдеры отвергают историю,
 * где TOOL-сообщение не следует за вызовом, поэтому связь обязательна.
 *
 * `attachments` по умолчанию пуст: сообщение без вложений — обычный случай, и оно должно
 * оставаться байт в байт таким же, как до появления вложений (см. тело запроса в
 * `OkHttpLocalChatClient`). Пустой список — это не «вложений нет, но формат новый»,
 * а именно прежняя строка `content`.
 */
data class LocalMessage(
    val id: String,
    val role: LocalRole,
    val text: String = "",
    val toolCalls: List<LocalToolCall> = emptyList(),
    val toolCallId: String? = null,
    val createdAt: Long = 0L,
    val attachments: List<LocalAttachment> = emptyList(),
)

/**
 * Модель принимает картинки.
 *
 * Признак берётся из каталога (`architecture.input_modalities` — разбор в
 * [LocalModelCatalog]), а не из имени модели: «gpt-4o» и «gpt-4o-mini» у разных посредников
 * отвечают по-разному, и угадывание по подстроке даёт мёртвую кнопку «приложить фото».
 * Пустой список означает «провайдер не сообщил» — тогда запрещать нельзя, отправляем.
 */
val LocalModelInfo.supportsImages: Boolean
    get() = inputModalities.any { it.equals("image", ignoreCase = true) }

/**
 * Почему вложение отправить нельзя; `null` — можно.
 *
 * Возвращает готовую русскую фразу для человека, а не исключение и не `false`: вызывающий
 * (экран и транспорт) обязан показать причину, иначе вложение исчезнет молча — худший из
 * возможных исходов, потому что человек уверен, что файл ушёл.
 *
 * [model] необязателен: если каталог уже известен, проверяется ещё и поддержка картинок
 * (`input_modalities`); без каталога проверяются только сами данные вложения.
 */
fun LocalAttachment.refusalReason(model: LocalModelInfo? = null): String? {
    val name = fileName?.takeIf { it.isNotBlank() }
        ?: if (kind == AttachmentKind.IMAGE) "картинка" else "файл"
    return when (kind) {
        AttachmentKind.IMAGE -> when {
            mimeType.isNotBlank() && !mimeType.startsWith("image/", ignoreCase = true) ->
                "«$name» — не картинка ($mimeType): как изображение отправить нельзя"

            dataBase64.isNullOrBlank() ->
                "у картинки «$name» нет данных — отправлять нечего"

            model != null && model.inputModalities.isNotEmpty() && !model.supportsImages ->
                "модель «${model.id}» не принимает изображения " +
                    "(провайдер сообщает input_modalities: ${model.inputModalities.joinToString(", ")})"

            else -> null
        }

        AttachmentKind.TEXT_FILE -> when {
            !textContent.isNullOrEmpty() -> null

            !dataBase64.isNullOrBlank() ->
                "файл «$name» двоичный ($mimeType): провайдер принимает только текст и картинки — " +
                    "сохрани документ как текст или приложи его страницей-картинкой"

            else -> "файл «$name» пуст — отправлять нечего"
        }

        AttachmentKind.DOCUMENT -> when {
            !textContent.isNullOrBlank() -> null
            else -> "из документа «$name» не удалось извлечь текст — отправлять нечего"
        }
    }
}

/**
 * Почему вложения сообщения отправить нельзя; `null` — можно.
 *
 * Вложения поддержаны только у сообщений пользователя: у `assistant` и `tool` провайдер
 * ждёт строку `content`, и массив частей там — гарантированный отказ запроса целиком,
 * то есть потеря не одного вложения, а всего хода.
 */
fun LocalMessage.attachmentRefusal(model: LocalModelInfo? = null): String? {
    if (attachments.isEmpty()) return null
    if (role != LocalRole.USER) {
        return "вложения поддержаны только в сообщениях пользователя, а не в «${role.name.lowercase()}»"
    }
    attachments.forEachIndexed { index, attachment ->
        attachment.refusalReason(model)?.let { return "вложение №${index + 1}: $it" }
    }
    return null
}

/** Описание инструмента для провайдера (OpenAI function schema). */
data class LocalToolSchema(val name: String, val description: String, val parametersJson: String)

/**
 * События одного шага диалога.
 *
 * Закрытый набор: экран и агентный цикл должны исчерпывающе разбирать его через `when`.
 * Новый вариант добавляет координатор, а не воркер — иначе разбор в чужом модуле
 * перестанет компилироваться посередине волны.
 */
sealed interface LocalChatEvent {
    /** Кусок текста ответа. */
    data class Delta(val text: String) : LocalChatEvent

    /** Кусок размышлений (провайдеры отдают их отдельным полем). В ленту не идёт как ответ. */
    data class ReasoningDelta(val text: String) : LocalChatEvent

    /** Модель просит выполнить инструмент. */
    data class ToolRequested(val call: LocalToolCall) : LocalChatEvent

    /** Инструмент выполнен; `preview` — короткая строка для карточки в ленте. */
    data class ToolFinished(val callId: String, val ok: Boolean, val preview: String) : LocalChatEvent

    /** Шаг модели закончен. Пустой текст при непустых вызовах — норма (только инструменты). */
    data class AssistantFinished(val fullText: String, val toolCalls: List<LocalToolCall>) : LocalChatEvent

    /**
     * Расход шага. Приходит ПЕРЕД [AssistantFinished], если провайдер его отдал.
     *
     * Порядок важен: экран обновляет пилюлю контекста тем же кадром, которым закрывается
     * шаг, — иначе между «ответ пришёл» и «процент обновился» виден скачок. Провайдер,
     * не приславший `usage`, просто не присылает это событие.
     */
    data class UsageReported(val usage: LocalUsage) : LocalChatEvent

    /** Ошибка шага. `retryable` — провайдер/сеть, а не логика. */
    data class Error(val message: String, val retryable: Boolean = false) : LocalChatEvent
}

/** Метаданные локального диалога для списка. */
data class LocalSession(val id: String, val title: String, val updatedAt: Long)
