package ru.mars.mobile

/**
 * Слэш-команды композера — чистая политика (без Android): разбор текста, таблица
 * команд для `/help`, валидация аргументов.
 *
 * Паритет с WebUI (`static/commands.js`), но ТОЛЬКО те команды, для которых у
 * мобильного клиента есть подтверждённый транспорт:
 * - `/stop`     — `GET /api/chat/cancel` через [ru.mars.network.repository.ChatRepository.cancelRequest];
 * - `/new`      — `POST /api/session/new`;
 * - `/retry`    — `POST /api/session/retry`;
 * - `/undo`     — `POST /api/session/undo`;
 * - `/branch`   — `POST /api/session/branch`;
 * - `/clear`    — `POST /api/session/clear`;
 * - `/title`    — `POST /api/session/rename`;
 * - `/truncate` — `POST /api/session/truncate`;
 * - `/steer`    — `POST /api/chat/steer`;
 * - `/reasoning`— `GET/POST /api/reasoning`;
 * - `/goal`     — обслуживается [GoalLoopPolicy] (своя логика и своя карточка).
 *
 * Остальные команды WebUI (`/model`, `/theme`, `/skills`, …) на телефоне не
 * поддержаны транспортом: [resolve] отвечает про них честным `Unavailable`, а не
 * выдуманным поведением, и такой текст НЕ уходит в модель как промпт.
 *
 * Исполнение (сеть, корутины, статус) живёт в ChatSlashCommandsSection.kt.
 */

/** Разобранная команда: [name] — в нижнем регистре без `/`, [args] — как набраны (trimmed). */
data class SlashCommand(val name: String, val args: String)

/** Строка таблицы команд: [usage] — как команду набирать, [description] — что она делает. */
data class SlashCommandSpec(
    val name: String,
    val usage: String,
    val description: String,
)

/** Итог разбора: команда поддержана, известна-но-недоступна, либо имя неизвестно. */
sealed interface SlashCommandResolution {
    data class Ready(val command: SlashCommand, val spec: SlashCommandSpec) : SlashCommandResolution
    data class Unavailable(val command: SlashCommand) : SlashCommandResolution
    data class Unknown(val command: SlashCommand) : SlashCommandResolution
}

/**
 * Что делать с `/reasoning <аргумент>`. Отдельный тип, чтобы Activity не сравнивала
 * строки: «показать текущее», «включить/выключить показ», «сменить уровень».
 */
sealed interface ReasoningRequest {
    /** Аргумента нет — показать текущее состояние (GET /api/reasoning). */
    object ShowCurrent : ReasoningRequest

    /** `show` — включить показ размышлений (`POST /api/reasoning {"display":"show"}`). */
    object Show : ReasoningRequest

    /** `hide` — выключить показ размышлений (`POST /api/reasoning {"display":"hide"}`). */
    object Hide : ReasoningRequest

    /** Уровень мышления: none|minimal|low|medium|high|xhigh|max (`POST /api/reasoning {"effort":…}`). */
    data class Effort(val effort: String) : ReasoningRequest
}

object SlashCommandPolicy {

    const val PREFIX = "/"

    /** Подсказка на неизвестное имя: честно говорим «не знаю» и куда смотреть. */
    const val UNKNOWN_HINT = " — неизвестная команда. /help — список команд"

    const val TITLE_USAGE = "Использование: /title <новый заголовок>"

    const val TRUNCATE_USAGE = "Использование: /truncate <N> — N неотрицательное целое (сколько сообщений оставить)"

    const val REASONING_USAGE = "Использование: /reasoning <show|hide|none|minimal|low|medium|high|xhigh|max>"

    const val STEER_USAGE = "Использование: /steer <текст> — уточнение в идущий ход"

    /** Ход не идёт: и `/stop`, и `/steer` отвечают этим, а не делают вид, что сработали. */
    const val NO_TURN = "Ход не идёт"

    /** Уровни мышления, которые принимает сервер (`api/config.VALID_REASONING_EFFORTS` + `none`). */
    val REASONING_EFFORTS = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

    /**
     * Таблица поддержанных команд в порядке показа в `/help`. Порядок — от «что это
     * вообще такое» к операциям над диалогом и ходом.
     */
    val SPECS: List<SlashCommandSpec> = listOf(
        SlashCommandSpec("help", "/help", "список команд"),
        SlashCommandSpec("stop", "/stop", "остановить текущий ход"),
        SlashCommandSpec("new", "/new", "новый диалог"),
        SlashCommandSpec("retry", "/retry", "повторить последний ход"),
        SlashCommandSpec("undo", "/undo", "отменить последний обмен"),
        SlashCommandSpec("branch", "/branch [название]", "ветка диалога"),
        SlashCommandSpec("clear", "/clear", "очистить историю диалога"),
        SlashCommandSpec("title", "/title <текст>", "переименовать диалог"),
        SlashCommandSpec("truncate", "/truncate <N>", "оставить N сообщений"),
        SlashCommandSpec("steer", "/steer <текст>", "уточнение в идущий ход"),
        SlashCommandSpec("reasoning", "/reasoning [show|hide|<уровень>]", "уровень размышлений"),
        SlashCommandSpec("goal", "/goal [статус|пауза|сброс|<цель>]", "цикл цели"),
    )

    /**
     * Команды WebUI, у которых на телефоне НЕТ транспорта. Держим их отдельным
     * списком: человек получает «недоступно на телефоне», а не «неизвестная
     * команда» (она существует — просто не здесь), и текст не уходит в модель.
     */
    val UNAVAILABLE_NAMES: List<String> = listOf(
        "btw", "background", "personality", "yolo", "theme", "workspace", "skills",
        "use", "queue", "interrupt", "compress", "compact", "usage", "status", "model", "voice",
    )

    private val specByName: Map<String, SlashCommandSpec> = SPECS.associateBy { it.name }

    private val unavailableSet: Set<String> = UNAVAILABLE_NAMES.toSet()

    /** Текст начинается с `/` — значит, его обслуживает клиент, а не модель. */
    fun isCommand(text: String): Boolean = parse(text) != null

    /**
     * Разбирает текст в команду. `null` — это НЕ команда (обычный текст): он уходит
     * в модель как промпт. Одинокий `/` командой не считается — это недописанный
     * ввод, а не имя.
     */
    fun parse(text: String): SlashCommand? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(PREFIX)) return null
        val body = trimmed.substring(PREFIX.length)
        val name = body.takeWhile { !it.isWhitespace() }.lowercase()
        // Имя команды — слово: `/` и `//` (недописанный ввод, слэш в тексте) командой не
        // считаются, иначе человек получил бы «неизвестная команда» на пустом месте.
        if (name.isEmpty() || !name.first().isLetter()) return null
        return SlashCommand(name = name, args = body.substring(name.length).trim())
    }

    /** Полный разбор: `null` — не команда; иначе — что с ней делать. */
    fun resolve(text: String): SlashCommandResolution? {
        val command = parse(text) ?: return null
        val spec = specByName[command.name]
        return when {
            spec != null -> SlashCommandResolution.Ready(command, spec)
            command.name in unavailableSet -> SlashCommandResolution.Unavailable(command)
            else -> SlashCommandResolution.Unknown(command)
        }
    }

    /** Строка таблицы по имени (без `/`), `null` — имя не из таблицы. */
    fun spec(name: String): SlashCommandSpec? = specByName[name.trim().lowercase().removePrefix(PREFIX)]

    fun unknownMessage(name: String): String = "/${name.trim()}$UNKNOWN_HINT"

    fun unavailableMessage(name: String): String =
        "/${name.trim()} недоступна на телефоне — этой команде здесь нечего выполнять. /help — что доступно"

    /** Аргумент `/truncate`: неотрицательное целое, иначе `null` (Activity покажет [TRUNCATE_USAGE]). */
    fun truncateCount(args: String): Int? {
        val raw = args.trim()
        if (raw.isEmpty()) return null
        // Только цифры: "5 5", "5.0", "0x5" и «5 сообщений» — не число сообщений.
        if (!raw.all { it.isDigit() }) return null
        val value = raw.toIntOrNull() ?: return null
        return if (value < 0) null else value
    }

    /** Аргумент `/reasoning`; `null` — недопустимое значение (Activity покажет [REASONING_USAGE]). */
    fun reasoningRequest(args: String): ReasoningRequest? {
        val raw = args.trim().lowercase()
        return when {
            raw.isEmpty() -> ReasoningRequest.ShowCurrent
            raw == "show" || raw == "on" -> ReasoningRequest.Show
            raw == "hide" || raw == "off" -> ReasoningRequest.Hide
            raw in REASONING_EFFORTS -> ReasoningRequest.Effort(raw)
            else -> null
        }
    }

    /** Текст `/help`: доступные команды с описаниями и честный список недоступных. */
    fun helpText(): String = buildString {
        append(SPECS.joinToString("\n") { "${it.usage} — ${it.description}" })
        append("\nНедоступно на телефоне: ")
        append(UNAVAILABLE_NAMES.joinToString(" ") { "$PREFIX$it" })
    }
}
