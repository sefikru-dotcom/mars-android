package ru.mars.mobile

import ru.mars.network.model.ChatEvent

/**
 * Решения отправки хода и разбора его стрима — чистая JVM-политика (без Android).
 *
 * Раньше эти ветвления жили внутри `sendMessage`/`startSendStream` вперемешку с эффектами
 * (сеть, view, тосты), поэтому их нельзя было проверить без устройства. Здесь остались
 * ровно решения, Activity — хост эффектов: она по-прежнему создаёт сессию, запускает
 * корутину, рисует и пишет статусы.
 *
 * Что закреплено политикой:
 * 1. Маршрут нажатия «отправить»: только чтение / уже идёт ход (=> steer) / команда `/goal`
 *    / обычная отправка. Дедупликация повторной отправки — это и есть правило
 *    «пока ход в статусе TYPING, повторный тап становится steer, а не вторым POST»:
 *    второго сообщения в одном ходе не появляется.
 * 2. Учёт итогов первичного collect ([TurnTally]): доставлен ли ход, был ли терминальный
 *    `Done`, была ли серверная ошибка, подавлен ли транспортный обрыв, отклонён ли креденшел.
 * 3. Решение после закрытия стрима: реавторизация / восстановление по Last-Event-ID /
 *    обычное завершение. Восстановление возможно ТОЛЬКО для доставленного хода без `Done`
 *    и без терминальной серверной ошибки — иначе reconnect лишь отреплеил бы ту же ошибку.
 * 4. Отказ серверного бэкенда в голосовом ходе (`409 unsupported_voice_mode_backend`):
 *    ход не доставлен, повтор бессмысленен и запрещён ([QueueOutcome.REJECTED]) — ни
 *    автоматический, ни через «успех» очереди. Тихой подмены обычным текстом здесь нет:
 *    текст остаётся у человека, но отправляет его он сам.
 */
class ChatTurnDispatcher {

    /** Маршрут нажатия «отправить». */
    enum class Route {
        /** Сессия read-only: отправлять нечего. */
        BLOCKED_READ_ONLY,

        /** Ход уже идёт: тап превращается в steer (он же дедупликация повторной отправки). */
        STEER,

        /** Команда `/goal` обслуживается локально, в модель не уходит. */
        GOAL_COMMAND,

        /** Обычная отправка. */
        SEND,
    }

    /** Входные данные маршрута: всё, что нужно для решения, и ничего больше. */
    data class SendInput(
        val readOnly: Boolean,
        val typing: Boolean,
        val goalCommand: Boolean,
    )

    /** Маршрут нажатия «отправить» с приоритетом проверок как в прежнем inline-коде. */
    fun route(input: SendInput): Route = when {
        input.readOnly -> Route.BLOCKED_READ_ONLY
        input.typing -> Route.STEER
        input.goalCommand -> Route.GOAL_COMMAND
        else -> Route.SEND
    }

    /**
     * Итоги первичного collect одного хода.
     *
     * `delivered` — сервер принял запрос (любое не-ошибочное событие); `sawDone` —
     * пришёл терминальный `Done` (никогда не ретраится); `sawAppError` — серверная
     * app/tool-ошибка (терминальна для хода); `suppressedTransportError` — подавленный
     * ТРАНСПОРТНЫЙ обрыв (в транскрипт не пишется, ход уходит в recovery);
     * `credentialRejected` — 401: путь принудительной реавторизации;
     * `turnFailed` — сообщение не дошло до сервера (сессия не создана / стрим не стартовал);
     * `voiceModeRejected` — сервер отказал голосовому режиму ДО side effects: это не сбой
     * связи, поэтому ни ретрай, ни «успех» ([shouldRetryQueued] / [shouldConfirmQueued]).
     */
    class TurnTally {
        var delivered: Boolean = false
        var sawDone: Boolean = false
        var sawAppError: Boolean = false
        var suppressedTransportError: ChatEvent.Error? = null
        var credentialRejected: Boolean = false
        var turnFailed: Boolean = false
        var voiceModeRejected: Boolean = false

        /** Серверная ошибка закрыла ход без транспортного сбоя — reconnect бессмыслен. */
        val appErrorTerminal: Boolean get() = sawAppError && suppressedTransportError == null
    }

    /** Что делать с кадром стрима. */
    enum class FrameDecision {
        /** Транспортный обрыв после доставки: в транскрипт не пишем, статус — «переподключаюсь». */
        SUPPRESS_TRANSPORT,

        /**
         * Сервер отказал голосовому режиму (`409 unsupported_voice_mode_backend`): сырой
         * HTTP-текст в пузырь не пишется, ход не ретраится, человеку — понятное объяснение.
         */
        REJECT_VOICE_MODE,

        /** Обычный кадр: применяется к состоянию хода и рисуется. */
        APPLY,
    }

    /**
     * Учитывает кадр в [tally] и говорит, применять ли его к транскрипту.
     *
     * @param transportShaped кадр-ошибка похож на транспортный обрыв ([TransportFailureClassifier]);
     *   считается вызывающей стороной ровно в прежнем порядке проверок (только для `Error`
     *   после доставки и при известном `stream_id`), чтобы классификатор не дёргался зря.
     */
    fun onFrame(event: ChatEvent, tally: TurnTally, transportShaped: Boolean): FrameDecision {
        // Отказ голосовому режиму проверяется ПЕРВЫМ: он приходит как обычный ChatEvent.Error
        // до доставки, и без отдельной ветки попал бы в общий путь «сообщение не дошло»,
        // то есть в авторетрай очереди и в сырой «Ошибка: HTTP 409 …» в транскрипте.
        if (event is ChatEvent.Error && VoiceModeRejectionPolicy.isVoiceModeRejected(event.message)) {
            tally.voiceModeRejected = true
            return FrameDecision.REJECT_VOICE_MODE
        }
        if (event !is ChatEvent.Error) tally.delivered = true
        if (event is ChatEvent.Done) tally.sawDone = true
        val suppress = event is ChatEvent.Error &&
            tally.delivered &&
            transportShaped &&
            !AuthRejectionPolicy.isCredentialRejected(event.message)
        if (suppress && event is ChatEvent.Error) {
            tally.suppressedTransportError = event
            return FrameDecision.SUPPRESS_TRANSPORT
        }
        if (event is ChatEvent.Error) {
            tally.sawAppError = true
            if (AuthRejectionPolicy.isCredentialRejected(event.message)) tally.credentialRejected = true
            if (!tally.delivered) tally.turnFailed = true
        }
        return FrameDecision.APPLY
    }

    /** Итог хода после закрытия первичного стрима. */
    enum class StreamOutcome {
        /** Креденшел отклонён сервером — экран уходит на логин. */
        REAUTH,

        /** Доставленный ход оборвался без `Done` — единственный recovery-job по Last-Event-ID. */
        RECOVER,

        /** Ход завершён здесь. */
        COMPLETE,
    }

    /**
     * Решение после закрытия стрима.
     *
     * @param streamId известный `stream_id` хода: без него attach к стриму невозможен.
     * @param recoveryAvailable инициализирован ли контроллер восстановления.
     */
    fun resolveAfterStream(
        tally: TurnTally,
        streamId: String?,
        recoveryAvailable: Boolean,
    ): StreamOutcome = when {
        tally.credentialRejected -> StreamOutcome.REAUTH
        tally.voiceModeRejected -> StreamOutcome.COMPLETE
        tally.delivered && !tally.sawDone && !tally.appErrorTerminal && streamId != null && recoveryAvailable ->
            StreamOutcome.RECOVER
        else -> StreamOutcome.COMPLETE
    }

    /** Итог хода для очереди: что делать со строкой, которую этот ход представлял. */
    enum class QueueOutcome {
        /** Креденшел отклонён: очередь не трогаем, экран уходит на логин. */
        NONE,

        /** Отказ голосовому режиму: строка снимается с автоотправки и ждёт осознанного решения. */
        REJECTED,

        /** Сообщение не дошло по транспортной причине — вернуть в очередь (лимит попыток). */
        RETRY,

        /** Ход доставлен и завершился без провала — очередь снимает строку. */
        CONFIRM,
    }

    /**
     * Единственное решение для хвоста хода.
     *
     * Приоритет важен: 401 (реавторизация) перекрывает всё, отказ голосовому режиму
     * перекрывает транспортный ретрай — иначе очередь бесконечно возвращала бы ход,
     * который сервер отвергает детерминированно.
     */
    fun queueOutcome(tally: TurnTally): QueueOutcome = when {
        tally.credentialRejected -> QueueOutcome.NONE
        tally.voiceModeRejected -> QueueOutcome.REJECTED
        tally.turnFailed -> QueueOutcome.RETRY
        else -> QueueOutcome.CONFIRM
    }

    /** Очередь возвращает недоставленное сообщение на повтор: доставленное не дублируется. */
    fun shouldRetryQueued(tally: TurnTally): Boolean = queueOutcome(tally) == QueueOutcome.RETRY

    /** Ход доставлен и завершился без провала — очередь снимает строку. */
    fun shouldConfirmQueued(tally: TurnTally): Boolean = queueOutcome(tally) == QueueOutcome.CONFIRM
}
