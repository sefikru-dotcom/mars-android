package ru.mars.mobile

import ru.mars.network.model.GoalContinuation
import ru.mars.network.model.GoalUpdate

/** Тон карточки цели — хост мапит его на цвет темы. */
enum class GoalCardTone {
    /** Цель в работе: идёт оценка прогресса или ход продолжается. */
    ACTIVE,

    /** Цель на паузе пользователем. */
    PAUSED,

    /** Цель закрыта: выполнена, очищена или её не было. */
    DONE,
}

/** Готовая к отрисовке карточка статуса цели (без Android-типов). */
data class GoalCardUi(
    val visible: Boolean,
    val title: String,
    val detail: String?,
    val tone: GoalCardTone,
    val contentDescription: String,
)

/**
 * Чистая политика карточки статуса цели и команды `/goal` (долг O14).
 *
 * Данные приходят ТОЛЬКО кадрами живого хода: `goal` ([GoalUpdate]) и
 * `goal_continue` ([GoalContinuation]) — сервер шлёт их в том же SSE-потоке, что
 * и токены ответа, поэтому карточка не требует отдельного запроса.
 *
 * Что закрыто здесь: распознавание команды в композере, тексты карточки и
 * локальный ответ на `/goal` / `/goal status`.
 *
 * Что НЕ закрыто и почему: постановка/пауза/снятие цели в WebUI идут через
 * `POST /api/goal` (`api/routes.py:14067` → `_handle_goal_command`). Сам маршрут
 * device-токену ДОСТУПЕН: он перечислен в `NATIVE_BEARER_EXACT_PATHS`
 * (`api/auth.py:106`), то есть 401 из-за allowlist здесь не будет. Незакрытым
 * остаётся КЛИЕНТ: в транспортном слое нет метода для `POST /api/goal`, поэтому
 * отправлять `/goal <текст>` обычным сообщением нельзя: сервер воспримет его как
 * текст промпта, цель не поставится, и пользователь получит ложное «сделано».
 * Поэтому управляющие подкоманды честно отвечают [CONTROL_UNAVAILABLE] — это
 * клиентский долг, а не серверный.
 */
object GoalLoopPolicy {

    /** Префикс команды в композере. */
    const val COMMAND = "/goal"

    /** Кадр `evaluating`: WebUI показывает ту же строку в статусе композера. */
    const val EVALUATING_MESSAGE = "Оцениваю прогресс по цели…"

    /** Ответ на `/goal` без активной цели. */
    const val NO_GOAL = "Активной цели нет. Поставить цель можно командой /goal <текст>."

    /** Честный отказ управляющих подкоманд (клиентский долг, маршрут сервером открыт). */
    const val CONTROL_UNAVAILABLE =
        "Управление целью с телефона пока недоступно: клиент ещё не отправляет " +
            "POST /api/goal. Статус цели показывает карточка в чате."

    /** Карточка скрыта (кадров ещё не было или цель закрыта без текста). */
    fun hidden(): GoalCardUi = GoalCardUi(
        visible = false,
        title = "",
        detail = null,
        tone = GoalCardTone.DONE,
        contentDescription = "",
    )

    /**
     * Кадр `goal` → карточка. `state = evaluating` перекрывает текст: сервер в этом
     * кадре ещё не знает исхода, а WebUI показывает именно строку оценки.
     */
    fun card(update: GoalUpdate): GoalCardUi {
        val state = update.state.trim().lowercase()
        if (state == "evaluating") {
            return GoalCardUi(
                visible = true,
                title = EVALUATING_MESSAGE,
                detail = null,
                tone = GoalCardTone.ACTIVE,
                contentDescription = EVALUATING_MESSAGE,
            )
        }
        val message = update.message?.trim().orEmpty()
        if (message.isEmpty()) {
            // Кадр без текста (например, «цели нет»): показывать нечего.
            return hidden()
        }
        return fromMessage(message, toneFor(state, update.messageKey))
    }

    /**
     * Кадр `goal_continue` → карточка. Сервер уже поставил продолжение в очередь;
     * текст продолжения показывает [GoalContinuation.continuationPrompt] отдельной
     * строкой, чтобы пользователь видел, что именно уйдёт следующим сообщением.
     */
    fun continuationCard(continuation: GoalContinuation): GoalCardUi {
        val message = continuation.message?.trim().orEmpty()
        val prompt = continuation.continuationPrompt.trim()
        val title = message.ifEmpty { "Продолжаю работу по цели" }
        val detail = buildList {
            message.lines().map(String::trim).filter(String::isNotEmpty).drop(1).forEach(::add)
            if (prompt.isNotEmpty()) add("Следующий шаг: $prompt")
        }.joinToString("\n").ifBlank { null }
        return GoalCardUi(
            visible = true,
            title = title.lines().first(),
            detail = detail,
            tone = GoalCardTone.ACTIVE,
            contentDescription = listOfNotNull(title.lines().first(), detail).joinToString(". "),
        )
    }

    /** Похоже ли сообщение на команду `/goal` (регистр и лишние пробелы не важны). */
    fun isCommand(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.startsWith(COMMAND, ignoreCase = true)) return false
        // "/goalkeeper" — не команда: после префикса допустим только конец строки или пробел.
        val rest = trimmed.substring(COMMAND.length)
        return rest.isEmpty() || rest.first().isWhitespace()
    }

    /** Аргументы команды без префикса: "" | "status" | "pause" | "текст цели". */
    fun commandArgs(text: String): String =
        if (!isCommand(text)) "" else text.trim().substring(COMMAND.length).trim()

    /** Подкоманда только читает состояние — её клиент обслуживает сам. */
    fun isStatusCommand(args: String): Boolean {
        val a = args.trim().lowercase()
        return a.isEmpty() || a == "status"
    }

    /**
     * Управляющая подкоманда (`pause` / `resume` / `clear` / `stop` / `done` /
     * текст новой цели) — требует `POST /api/goal`: ручка device-токену открыта
     * (`api/auth.py:106`), но клиентского метода для неё пока нет.
     */
    fun isControlCommand(args: String): Boolean = !isStatusCommand(args)

    /** Ответ на `/goal` и `/goal status`: карточка, если она есть, иначе [NO_GOAL]. */
    fun statusReply(card: GoalCardUi?): String {
        if (card == null || !card.visible) return NO_GOAL
        val detail = card.detail?.takeIf(String::isNotBlank)
        return if (detail == null) card.title else "${card.title}\n$detail"
    }

    /** Ответ на управляющую подкоманду: локально выполнить её нечем — говорим прямо. */
    fun controlReply(args: String): String {
        val sub = args.trim().lowercase()
        val what = when (sub) {
            "pause", "пауза" -> "Пауза"
            "resume", "продолжить" -> "Продолжение"
            "clear", "stop", "done", "снять", "стоп" -> "Снятие цели"
            else -> "Постановка цели"
        }
        return "$what: $CONTROL_UNAVAILABLE"
    }

    private fun fromMessage(message: String, tone: GoalCardTone): GoalCardUi {
        val lines = message.lines().map(String::trim).filter(String::isNotEmpty)
        val title = lines.first()
        val detail = lines.drop(1).joinToString("\n").ifBlank { null }
        return GoalCardUi(
            visible = true,
            title = title,
            detail = detail,
            tone = tone,
            contentDescription = listOfNotNull(title, detail).joinToString(". "),
        )
    }

    /**
     * Тон по состоянию и `message_key`. Ключи — контракт сервера
     * (`api/goals.py`): `goal_paused` / `goal_resumed` / `goal_set` /
     * `goal_continuing` / `goal_cleared` / `goal_no_goal`.
     */
    private fun toneFor(state: String, messageKey: String?): GoalCardTone {
        val key = messageKey?.trim()?.lowercase().orEmpty()
        return when {
            key == "goal_paused" || state == "paused" -> GoalCardTone.PAUSED
            key in CLEARED_KEYS || state in CLEARED_STATES -> GoalCardTone.DONE
            else -> GoalCardTone.ACTIVE
        }
    }

    private val CLEARED_KEYS = setOf("goal_cleared", "goal_no_goal", "goal_done")
    private val CLEARED_STATES = setOf("idle", "done", "cleared", "paused_done")
}
