package ru.mars.mobile

/**
 * Один набор инструментов (тулсет) Hermes, доступный для включения в сессии.
 *
 * [name] — ровно то имя, которое понимает сервер (`POST /api/session/toolsets`
 * валидирует непустой список строк); [title] и [description] — только подписи
 * интерфейса и на провод не уходят.
 */
data class ToolsetOption(
    val name: String,
    val title: String,
    val description: String,
)

/**
 * Справочник тулсетов и валидация выбора сессии (A9).
 *
 * Список СТАТИЧЕСКИЙ и намеренно не грузится по сети: каталог WebUI приходит с
 * `GET /api/mcp/servers`, а этот путь не входит в белый список device-Bearer и
 * отдаёт 401. Здесь ровно тот набор, который фактически существует в Hermes.
 */
object SessionToolsetsPolicy {
    /** Все тулсеты в порядке показа — от базовых к вспомогательным. */
    val ALL: List<ToolsetOption> = listOf(
        ToolsetOption("browser", "Браузер", "Управление страницей в реальном браузере"),
        ToolsetOption("clarify", "Уточнения", "Вопросы пользователю по ходу выполнения"),
        ToolsetOption("code_execution", "Выполнение кода", "Запуск кода с возвратом результата"),
        ToolsetOption("cronjob", "Расписания", "Отложенные и повторяющиеся задачи"),
        ToolsetOption("delegation", "Делегирование", "Передача подзадач отдельным агентам"),
        ToolsetOption("file", "Файлы", "Чтение, запись и правка файлов"),
        ToolsetOption("image_gen", "Генерация изображений", "Создание картинок по описанию"),
        ToolsetOption("memory", "Память", "Долговременная память между сессиями"),
        ToolsetOption("session_search", "Поиск по сессиям", "Поиск в прошлых диалогах"),
        ToolsetOption("skills", "Навыки", "Пользовательские процедуры и рецепты"),
        ToolsetOption("terminal", "Терминал", "Команды оболочки на хосте"),
        ToolsetOption("todo", "Список задач", "Ведение списка шагов работы"),
        ToolsetOption("web", "Интернет и поиск", "Поиск и разбор веб-страниц"),
        ToolsetOption("webhook", "Вебхуки", "Исходящие уведомления по событию"),
    )

    private val BY_NAME: Map<String, ToolsetOption> = ALL.associateBy { it.name }

    /** Подпись тулсета для интерфейса; неизвестное имя показывается как есть. */
    fun title(name: String): String = BY_NAME[name]?.title ?: name

    /**
     * Приводит выбранные имена к порядку справочника, выбрасывает пустые и
     * неизвестные. Пустой результат означает либо сброс (см. [resolvePayload]),
     * либо нечего отправлять — решает вызывающий.
     */
    fun normalize(names: Collection<String>): List<String> {
        val cleaned = names.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return ALL.map { it.name }.filter { it in cleaned }
    }

    /**
     * Полезная нагрузка для `POST /api/session/toolsets`:
     * - `null` — сброс к серверному набору по умолчанию;
     * - непустой список — ограничение сессии этим набором.
     *
     * Пустой список сервер отвергает, поэтому «ничего не выбрано» трактуется как
     * сброс, а не как ошибка валидации — пользователь не может зайти в тупик.
     */
    fun payloadFor(selected: Collection<String>): List<String>? =
        normalize(selected).takeIf { it.isNotEmpty() }

    /**
     * Состояние чипа: сводка выбора. `null` (сервер вернул `enabled_toolsets: null`)
     * означает «набор по умолчанию» — это НЕ пустой набор.
     */
    fun chipLabel(enabledToolsets: List<String>?): String {
        val names = enabledToolsets?.let { normalize(it) } ?: return "Инструменты: все"
        if (names.isEmpty()) return "Инструменты: все"
        if (names.size <= 2) return "Инструменты: " + names.joinToString(", ") { title(it) }
        return "Инструменты: ${names.size} выбрано"
    }

    /** Описание чипа для TalkBack — полное перечисление, без сокращений. */
    fun chipDescription(enabledToolsets: List<String>?): String {
        val names = enabledToolsets?.let { normalize(it) } ?: return "Набор инструментов сессии: по умолчанию, все"
        if (names.isEmpty()) return "Набор инструментов сессии: по умолчанию, все"
        return "Набор инструментов сессии: " + names.joinToString(", ") { title(it) }
    }

    /** Строка-итог после сохранения. */
    fun savedMessage(toolsets: List<String>?): String {
        val names = toolsets?.let { normalize(it) }
        return if (names.isNullOrEmpty()) "Инструменты: набор по умолчанию" else "Инструменты: выбрано ${names.size}"
    }
}
