package ru.mars.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.mars.network.model.ChatEvent
import ru.mars.network.repository.MidTurnRecoveryCoordinator
import ru.mars.network.repository.MidTurnRecoveryCoordinator.RecoveryDecision
import ru.mars.network.repository.MidTurnRecoveryCoordinator.StreamFailure
import ru.mars.network.repository.StreamResumeTracker

/**
 * Транспорт recovery-цикла: ТОЛЬКО переоткрытие уже доставленного хода
 * (`GET /api/chat/stream?stream_id=…` + `Last-Event-ID`). Интерфейс намеренно не имеет
 * метода отправки сообщения: повторный `POST /api/chat/start` после доставки структурно
 * невозможен — ход продолжается, а не перезапускается.
 *
 * [onEventId] обязан вызываться для КАЖДОГО кадра с `id:` ДО маппинга (контракт
 * `ChatRepository.attachToStream`), чтобы курсор [StreamResumeTracker] продвигался даже на
 * кадрах, которые не становятся событиями транскрипта.
 */
fun interface RecoveryStreamTransport {
    fun attach(streamId: String, lastEventId: String?, onEventId: (String) -> Unit): Flow<ChatEvent>
}

/**
 * Единственный recovery-job на один доставленный серверный ход: цикл «attach → обрыв →
 * решение [MidTurnRecoveryCoordinator] → reattach c `Last-Event-ID`» поверх инъектируемого
 * [RecoveryStreamTransport].
 *
 * Слой чистый JVM (kotlinx-coroutines, без Android): хост ([ChatActivity]) даёт scope,
 * транспорт и [Listener]; все решения и состояние цикла живут здесь и покрыты JVM-тестами
 * с фейковым транспортом ([MidTurnRecoveryControllerTest]).
 *
 * Контракт:
 * - ОДИН активный job: любой новый вход ([beginRecoveryAfterDrop]/[attachActiveTurn]) и
 *   [cancelRecovery] повышают поколение — поздние события и id-кадры убитого цикла
 *   отбрасываются generation-guard'ом;
 * - терминальный `ChatEvent.Done` НИКОГДА не ретраится;
 * - транспортные `ChatEvent.Error` ([TransportFailureClassifier]) в подавленном виде не
 *   уходят в транскрипт (иначе reconnect-шум дублировал бы текст пузыря) — они всплывают
 *   только через [Listener.onGiveUp], если восстановление окончательно провалилось;
 * - серверные tool/app-ошибки проходят в транскрипт как есть и завершают цикл без ретрая;
 * - офлайн: цикл паркуется ([RecoveryDecision.WaitForNetwork]) и просыпается от
 *   [onNetworkChanged]`true)` — тем же failure-входом, backoff ядра даёт немедленный
 *   reattach после перехода offline→online;
 * - дубликаты при reattach исключены серверным replay от курсора `Last-Event-ID`
 *   (трекер продвигается на всех кадрах) и дедупликацией диалогов по id в
 *   `InteractiveRequestController`.
 */
class MidTurnRecoveryController(
    private val scope: CoroutineScope,
    private val transport: RecoveryStreamTransport,
    private val listener: Listener,
    private val coordinator: MidTurnRecoveryCoordinator = MidTurnRecoveryCoordinator(),
    private val classifier: TransportFailureClassifier = TransportFailureClassifier(),
    private val sleeper: Sleeper = DelaySleeper,
) {

    /** Точка сна между попытками; в тестах заменяется записывающей заглушкой. */
    fun interface Sleeper {
        suspend fun sleep(ms: Long)
    }

    /** Продуктовый сон: [delay] (отменяемый — stop/навигация прерывают ожидание мгновенно). */
    object DelaySleeper : Sleeper {
        override suspend fun sleep(ms: Long) {
            if (ms > 0L) delay(ms)
        }
    }

    /** Тонкий wiring-контракт хоста (Activity): все вызовы происходят в scope-диспетчере цикла. */
    interface Listener {
        /** Очередное событие стрима для обычного конвейера транскрипта (без подавленных транспортных ошибок). */
        fun onStreamEvent(event: ChatEvent)

        /** Attach/reattach стартует: `attempt` 0 — первое подключение, 1+ — восстановление после обрыва. */
        fun onAttachAttempt(attempt: Int, resumeFromEventId: String?)

        /** Цикл припаркован до возврата сети (офлайн-парковка). */
        fun onRecoveryParkedOffline()

        /** Наблюден терминальный `Done`: ход завершён штатно, ретраев не будет. */
        fun onTurnCompleted()

        /** Ход завершился серверной ошибкой (`error`/`apperror`-кадр уже в транскрипте) — без ретрая. */
        fun onTurnEndedInError()

        /** Стрим на сервере исчез (404/истёкший журнал): перезагрузить историю сессии. */
        fun onReloadSession()

        /** Восстановление исчерпано/невозможно; [suppressedTransportError] — последняя подавленная транспортная ошибка, если была. */
        fun onGiveUp(reason: String, suppressedTransportError: ChatEvent.Error?)
    }

    /** Общий курсор resume для send-пути и recovery-цикла (WHATWG last-event-id, ядро). */
    val resumeTracker = StreamResumeTracker()

    /** Generation guard: поздние события/кадры убитого цикла не проходят. */
    @Volatile
    private var generation = 0L

    private var job: Job? = null

    /**
     * Ворота офлайн-парковки как StateFlow (ленту кормит хост через [onNetworkChanged]):
     * при «моргании» сети (LOSING→OFFLINE — два `false` подряд) ротация
     * CompletableDeferred могла бы оставить await на устаревшем объекте навсегда;
     * StateFlow от гонки свободен (ждём условие, а не экземпляр).
     */
    private val networkFlow = MutableStateFlow(true)

    /** Есть ли живой recovery-job (guard против двойного attach в хосте). */
    val isRecovering: Boolean get() = job?.isActive == true

    /**
     * Новый пользовательский ход: убить предыдущий recovery, сбросить курсор (id чужого
     * стрима не должны протечь в `Last-Event-ID`) и серии отказов ядра.
     */
    fun onNewTurn() {
        cancelRecovery()
        coordinator.reset()
        resumeTracker.reset()
    }

    /**
     * Прервать recovery: stop, смена сессии/нового чата, logout, destroy. Job отменяется,
     * поколение растёт — поздние [Listener]-вызовы и id-кадры этого цикла больше не пройдут.
     */
    fun cancelRecovery() {
        generation += 1
        job?.cancel()
        job = null
    }

    /**
     * Переход сети от connectivity observer: кормит координатор (offline → парковка,
     * online → немедленный reattach) и открывает ворота припаркованного цикла.
     */
    fun onNetworkChanged(available: Boolean) {
        coordinator.onNetworkChanged(available)
        networkFlow.value = available
    }

    /**
     * Вход A — ход ДОСТАВЛЕН через `POST /api/chat/start` (был `stream_started`), но
     * первичный collect оборвался без терминального `Done`. Стартует единственный
     * recovery-job с уже накопленным failure (классификацию и решения делает ядро);
     * курсор `Last-Event-ID` берётся из [resumeTracker], который кормил send-путь.
     * Повторной отправки сообщения не происходит НИКОГДА — только attach.
     */
    fun beginRecoveryAfterDrop(streamId: String, dropError: ChatEvent.Error?): Job {
        cancelRecovery()
        val failure = StreamFailure(
            throwable = null,
            errorMessage = dropError?.message,
            lastEventId = resumeTracker.lastEventId,
        )
        return startLoop(streamId, initialFailure = failure, initialSuppressed = dropError)
    }

    /**
     * Вход B — деталь сессии сообщает активный ход: attach с нуля (курсора нет, журнал
     * сервер отдаст snapshot-backfill + live). Полный recovery-жизненный цикл: обрывы,
     * backoff, офлайн-парковка, 404 → [Listener.onReloadSession].
     */
    fun attachActiveTurn(streamId: String): Job {
        cancelRecovery()
        coordinator.reset()
        resumeTracker.reset()
        return startLoop(streamId, initialFailure = null, initialSuppressed = null)
    }

    // --- Цикл -----------------------------------------------------------------------------------

    private fun startLoop(streamId: String, initialFailure: StreamFailure?, initialSuppressed: ChatEvent.Error?): Job {
        val gen = generation
        val started = scope.launch {
            var failure = initialFailure
            var suppressed = initialSuppressed
            while (isActive && gen == generation) {
                val currentFailure = failure
                val outcome = if (currentFailure == null) {
                    listener.onAttachAttempt(0, resumeTracker.lastEventId)
                    runAttach(gen, streamId, resumeTracker.lastEventId)
                } else {
                    when (val decision = coordinator.decide(currentFailure)) {
                        is RecoveryDecision.ReattachAfter -> {
                            if (gen != generation) return@launch
                            listener.onAttachAttempt(decision.attempt, decision.resumeFromEventId)
                            sleeper.sleep(decision.delayMs)
                            if (!isActive || gen != generation) return@launch
                            runAttach(gen, streamId, decision.resumeFromEventId)
                        }

                        RecoveryDecision.WaitForNetwork -> {
                            if (gen != generation) return@launch
                            listener.onRecoveryParkedOffline()
                            networkFlow.first { it }
                            if (gen != generation) return@launch
                            // Проснулись: то же failure уходит на повторное decide — backoff
                            // ядра выдаст немедленную (0 мс) попытку после offline→online.
                            failure = currentFailure.copy(lastEventId = resumeTracker.lastEventId)
                            continue
                        }

                        RecoveryDecision.ReloadSession -> {
                            listener.onReloadSession()
                            return@launch
                        }

                        is RecoveryDecision.GiveUp -> {
                            listener.onGiveUp(decision.reason, suppressed)
                            return@launch
                        }
                    }
                }
                when (outcome) {
                    AttachOutcome.TurnCompleted -> {
                        listener.onTurnCompleted()
                        return@launch
                    }

                    AttachOutcome.AppErrorTerminal -> {
                        listener.onTurnEndedInError()
                        return@launch
                    }

                    AttachOutcome.Stale -> return@launch

                    is AttachOutcome.Dropped -> {
                        failure = outcome.failure
                        suppressed = outcome.suppressedError ?: suppressed
                    }
                }
            }
        }
        job = started
        return started
    }

    private sealed interface AttachOutcome {
        /** Наблюден терминальный `Done` — ход завершён, ретраи запрещены. */
        data object TurnCompleted : AttachOutcome

        /** Поток закрыт серверной ошибкой хода (не транспорт) — ошибка уже видна в транскрипте. */
        data object AppErrorTerminal : AttachOutcome

        /** Покоение устарело (stop/навигация/logout) — цикл молча завершается. */
        data object Stale : AttachOutcome

        /** Транспортный обрыв: курсор + последняя подавленная транспортная ошибка для decide. */
        data class Dropped(val failure: StreamFailure, val suppressedError: ChatEvent.Error?) : AttachOutcome
    }

    private suspend fun runAttach(gen: Long, streamId: String, resumeFrom: String?): AttachOutcome {
        var sawDone = false
        var appError: ChatEvent.Error? = null
        var transportError: ChatEvent.Error? = null
        var throwable: Throwable? = null
        try {
            transport.attach(streamId, resumeFrom) { id ->
                // id-кадры убитого цикла не должны двигать курсор нового поколения.
                if (gen == generation) resumeTracker.onFrameId(id)
            }.collect { event ->
                if (gen != generation) throw CancellationException("recovery generation superseded")
                when {
                    event is ChatEvent.Done -> {
                        sawDone = true
                        listener.onStreamEvent(event)
                    }

                    event is ChatEvent.Error && classifier.isTransportShaped(event.message) -> {
                        // Подавляем: recoverable-обрыв не должен писать «Ошибка: …» в пузырь
                        // и ронять статус в ERROR — ход ещё жив и продолжится после reattach.
                        transportError = event
                    }

                    event is ChatEvent.Error -> {
                        // Серверная tool/app-ошибка — нормальное событие журнала хода.
                        appError = event
                        listener.onStreamEvent(event)
                    }

                    else -> {
                        // Первый ПРОВОЛОЧНЫЙ кадр успешного attach (не локальный
                        // `StreamStarted`, который репозиторий эмитит до запроса) доказывает
                        // живость соединения: ядро сбрасывает серию отказов и лестницу backoff.
                        if (event !is ChatEvent.StreamStarted) coordinator.onStreamHealthy()
                        listener.onStreamEvent(event)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            throwable = t
        }
        if (gen != generation) return AttachOutcome.Stale
        return when {
            sawDone -> AttachOutcome.TurnCompleted
            // Поток закрыт app-ошибкой без транспортного сбоя: ход завершился на сервере —
            // reattach лишь отреплеил бы ту же ошибку.
            transportError == null && throwable == null && appError != null -> AttachOutcome.AppErrorTerminal
            else -> AttachOutcome.Dropped(
                failure = StreamFailure(
                    throwable = throwable,
                    errorMessage = transportError?.message,
                    lastEventId = resumeTracker.lastEventId,
                ),
                suppressedError = transportError,
            )
        }
    }
}
