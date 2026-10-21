package ru.mars.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.mars.network.repository.ServerTtsResult

/**
 * Очередь озвучки фраз: синтез идёт на опережение, воспроизведение — строго по порядку,
 * остановка — мгновенная.
 *
 * Зачем: ответ приходит фразами, а звучать должен непрерывно. Пока играет фраза N,
 * синтезируется N+1 ([lookahead]), поэтому между фразами нет паузы на сеть. Параллелизм
 * синтеза ограничен [maxSynthesisParallelism] (по умолчанию 2): больше запросов к
 * `api/tts` одновременно — лишняя нагрузка на сервер и никакого выигрыша, потому что
 * порядок всё равно последовательный.
 *
 * Гарантии:
 * - ПОРЯДОК: фраза играет только когда все предыдущие доиграли или были пропущены;
 * - ПОКОЛЕНИЕ: у каждого [speak] есть поколение [generation]; [stopAll] его увеличивает и
 *   стирает очередь, поэтому поздний ответ синтеза (или поздний колбэк плеера) после
 *   остановки НИКОГДА не зазвучит — перебивание не «догоняет» пользователя обрывком
 *   старого ответа;
 * - ОТКАЗ ОДНОЙ ФРАЗЫ не рушит очередь: фраза пропускается с [onPhraseFailed], следующая
 *   синтезируется и играет;
 * - СИГНАЛЫ: [onFirstAudio] — первое реально зазвучавшее аудио (замер задержки),
 *   [onDrained] — всё доиграно (хост переводит hands-free-цикл в слушание).
 * - ЛИМИТЕР СЕРВЕРА: `api/tts` держит 2 с на клиента и отвечает `429`, когда фразы уходят
 *   подряд. Такой отказ повторяется по [retryPolicy] (пауза из `Retry-After`, ограничена
 *   сверху, отменяема) — иначе в речи появлялись бы дырки на ровном месте.
 *
 * Ядро чистое: зависимость от Android — только через [ServerTtsPlayback], поэтому весь
 * порядок, гонки и остановки проверяются юнит-тестами с фейками.
 *
 * @param client синтез одной фразы.
 * @param playback проигрывание готового аудио; обязан поддерживать [ServerTtsPlayback.playToEnd].
 * @param scope область для фонового синтеза (в приложении — `lifecycleScope`).
 * @param onFirstAudio первое аудио пошло в динамик (один раз на очередь до [onDrained]).
 * @param onDrained очередь пуста и всё доиграно.
 * @param onPhraseStarted КАЖДАЯ фраза пошла в динамик: её текст (для echo-эталона перебивания).
 *   Зовётся по одной фразе, а не «весь ответ сразу»: текст, который ещё не звучал, не должен
 *   считаться озвученным.
 * @param onPhraseFailed фраза пропущена: текст и короткая причина.
 * @param maxSynthesisParallelism предел одновременных запросов синтеза.
 * @param lookahead на сколько фраз вперёд синтезировать относительно играющей.
 * @param retryPolicy повтор фразы после `429`/`5xx` (ограниченный и отменяемый).
 * @param metrics метрики задержки (только числа, без текста); null — не собираем.
 */
class SpeechQueue(
    private val client: ServerTtsClient,
    private val playback: ServerTtsPlayback,
    private val scope: CoroutineScope,
    private val onFirstAudio: () -> Unit = {},
    private val onDrained: () -> Unit = {},
    private val onPhraseStarted: (text: String) -> Unit = {},
    private val onPhraseFailed: (text: String, reason: String) -> Unit = { _, _ -> },
    private val maxSynthesisParallelism: Int = DEFAULT_SYNTHESIS_PARALLELISM,
    private val lookahead: Int = DEFAULT_LOOKAHEAD,
    private val retryPolicy: SpeechRetryPolicy = SpeechRetryPolicy(),
    private val metrics: SpeechLatencyMetrics? = null,
) {

    /** Состояние фразы в очереди. Порядок переходов: QUEUED → SYNTHESIZING → READY → PLAYING. */
    private enum class SlotState { QUEUED, SYNTHESIZING, READY, PLAYING }

    /** Фраза очереди. [seq] задаёт порядок и никогда не переиспользуется. */
    private class Slot(val seq: Long, val text: String) {
        var state: SlotState = SlotState.QUEUED
        var bytes: ByteArray = EMPTY_BYTES
        var contentType: String = ""
    }

    private val lock = Any()
    private val slots = LinkedHashMap<Long, Slot>()
    private val jobs = HashMap<Long, Job>()

    /** Сквозной счётчик фраз: после [stopAll] новые фразы получают больший номер. */
    private var seqCounter: Long = 0L

    /** Номер фразы, которая должна зазвучать следующей. */
    private var nextSeq: Long = 1L

    private var inFlight: Int = 0
    private var playing: Boolean = false
    private var firstAudioReported: Boolean = false
    private var released: Boolean = false
    private var generationCounter: Int = 0

    /** Текущее поколение: растёт на каждом [stopAll] и [release]. */
    val generation: Int
        get() = synchronized(lock) { generationCounter }

    /** Что-то звучит прямо сейчас. */
    val isSpeaking: Boolean
        get() = synchronized(lock) { playing }

    /** Сколько фраз ещё в очереди (включая синтезируемые и играющую). */
    val pendingCount: Int
        get() = synchronized(lock) { slots.size }

    /** Сколько запросов синтеза в полёте (диагностика и тесты). */
    val inFlightSynthesis: Int
        get() = synchronized(lock) { inFlight }

    /**
     * Добавить фразу в очередь. Пустая строка игнорируется.
     *
     * @return `true`, если фраза принята; `false` — пустая строка или очередь освобождена.
     */
    fun speak(text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        val chosen: List<Slot>
        val gen: Int
        synchronized(lock) {
            if (released) return false
            seqCounter++
            val slot = Slot(seqCounter, clean)
            slots[slot.seq] = slot
            gen = generationCounter
            chosen = collectStartsLocked()
        }
        launchSynthesis(chosen, gen)
        return true
    }

    /** Добавить несколько фраз подряд (порядок сохраняется). */
    fun speakAll(phrases: List<String>) {
        phrases.forEach { speak(it) }
    }

    /**
     * Мгновенная остановка: тишина в динамике, отмена всех синтезов в полёте, пустая очередь.
     * Поздние ответы синтеза и колбэки плеера отбрасываются по поколению.
     */
    fun stopAll() {
        val toCancel: List<Job>
        synchronized(lock) {
            generationCounter++
            toCancel = jobs.values.toList()
            jobs.clear()
            slots.clear()
            inFlight = 0
            playing = false
            firstAudioReported = false
            nextSeq = seqCounter + 1
        }
        toCancel.forEach { job -> job.cancel() }
        runCatching { playback.stop() }
    }

    /** Освободить очередь и плеер навсегда (хост уничтожен). */
    fun release() {
        stopAll()
        synchronized(lock) { released = true }
        runCatching { playback.release() }
    }

    // --- синтез --------------------------------------------------------------------------------

    /**
     * Выбрать фразы, которые пора синтезировать: ближайшие к играющей, не больше
     * [maxSynthesisParallelism] одновременно и не дальше [lookahead].
     */
    private fun collectStartsLocked(): List<Slot> {
        if (released) return emptyList()
        val limit = nextSeq + lookahead
        val chosen = ArrayList<Slot>()
        for (slot in slots.values) {
            if (inFlight >= maxSynthesisParallelism) break
            if (slot.seq > limit) break
            if (slot.state != SlotState.QUEUED) continue
            slot.state = SlotState.SYNTHESIZING
            inFlight++
            chosen += slot
        }
        return chosen
    }

    /** Запустить синтез выбранных фраз; поколение фиксируется в замыкании. */
    private fun launchSynthesis(chosen: List<Slot>, gen: Int) {
        for (slot in chosen) {
            val job = scope.launch {
                val result = synthesizeWithRetry(gen, slot)
                onSynthesisFinished(gen, slot, result)
            }
            synchronized(lock) {
                // Синтез мог завершиться раньше, чем мы записали job (Unconfined/мгновенный
                // фейк): тогда слот уже READY или снят, и запоминать нечего.
                val stale = released || generationCounter != gen ||
                    slots[slot.seq] !== slot || slot.state != SlotState.SYNTHESIZING
                if (stale) {
                    if (released || generationCounter != gen) job.cancel()
                } else {
                    jobs[slot.seq] = job
                }
            }
        }
    }

    /** Синтез завершился: готовое аудио в слот, отказ — пропуск фразы. */
    private fun onSynthesisFinished(gen: Int, slot: Slot, result: ServerTtsResult) {
        var chosen: List<Slot> = emptyList()
        var failure: String? = null
        var drained = false
        synchronized(lock) {
            if (released || generationCounter != gen) return
            jobs.remove(slot.seq)
            if (inFlight > 0) inFlight--
            if (slots[slot.seq] !== slot) return
            val audio = result as? ServerTtsResult.Audio
            if (audio != null && audio.bytes.isNotEmpty()) {
                slot.bytes = audio.bytes
                slot.contentType = audio.contentType
                slot.state = SlotState.READY
            } else {
                failure = (result as? ServerTtsResult.Unavailable)?.reason ?: "empty_audio"
                dropLocked(slot)
            }
            chosen = collectStartsLocked()
            drained = slots.isEmpty() && inFlight == 0
        }
        if (failure != null) notifyPhraseFailed(slot.text, failure!!)
        launchSynthesis(chosen, gen)
        if (drained) notifyDrained() else pumpPlayback()
    }

    // --- воспроизведение -----------------------------------------------------------------------
    /**
     * Синтез одной фразы с ограниченным повтором на `429`/`5xx`.
     *
     * Повтор прерывается, если фраза перестала быть актуальной (перебивание, [stopAll],
     * [release]): проверка ДО и ПОСЛЕ ожидания, потому что отмена корутины рвёт `delay`,
     * но не гарантирует, что фраза ещё в очереди.
     */
    private suspend fun synthesizeWithRetry(gen: Int, slot: Slot): ServerTtsResult {
        var attempt = 1
        while (true) {
            metrics?.onSynthesisRequest()
            val result = try {
                client.synthesize(slot.text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Порты обязаны не бросать; страховка — пропуск фразы, а не падение.
                ServerTtsResult.Unavailable("transport")
            }
            val unavailable = result as? ServerTtsResult.Unavailable
            if (unavailable == null) return result
            val canRetry = attempt < retryPolicy.maxAttempts && retryPolicy.isRetryable(unavailable.reason)
            if (!canRetry || !isCurrent(gen, slot)) return result
            metrics?.onSynthesisRetry(unavailable.reason)
            retryPolicy.awaitRetry(attempt, unavailable.retryAfterMs)
            // Пока ждали, фразу могли остановить: поздний звук после «стоп» недопустим.
            if (!isCurrent(gen, slot)) return result
            attempt++
        }
    }

    /** Фраза всё ещё ждёт синтеза в этом же поколении. */
    private fun isCurrent(gen: Int, slot: Slot): Boolean = synchronized(lock) {
        !released && generationCounter == gen &&
            slots[slot.seq] === slot && slot.state == SlotState.SYNTHESIZING
    }

    /** Запустить следующую готовую фразу, если сейчас ничего не играет. */
    private fun pumpPlayback() {
        var toPlay: Slot? = null
        var drained = false
        synchronized(lock) {
            if (released || playing) return
            skipMissingLocked()
            val slot = slots[nextSeq]
            if (slot == null) {
                drained = slots.isEmpty() && inFlight == 0
            } else if (slot.state == SlotState.READY) {
                slot.state = SlotState.PLAYING
                playing = true
                toPlay = slot
            }
        }
        val slot = toPlay
        if (slot != null) startPlayback(slot)
        if (drained) notifyDrained()
    }

    /** Отдать фразу плееру; отказ плеера — пропуск фразы, а не остановка очереди. */
    private fun startPlayback(slot: Slot) {
        val gen = synchronized(lock) { generationCounter }
        val accepted = runCatching {
            playback.playToEnd(
                slot.bytes,
                slot.contentType,
                onFinished = { finishPhrase(gen, slot) },
                onFailed = { failPhrase(gen, slot, "playback") },
            )
        }.getOrDefault(false)
        if (!accepted) {
            failPhrase(gen, slot, "playback_rejected")
            return
        }
        // Фраза реально пошла в динамик: только теперь её текст становится эталоном эха.
        runCatching { onPhraseStarted(slot.text) }
        val first = synchronized(lock) {
            if (released || generationCounter != gen) {
                false
            } else if (firstAudioReported) {
                false
            } else {
                firstAudioReported = true
                true
            }
        }
        if (first) runCatching { onFirstAudio() }
    }

    /** Фраза доиграла: снять со счёта и запустить следующую. */
    private fun finishPhrase(gen: Int, slot: Slot) {
        advance(gen, slot, failure = null)
    }

    /** Фраза не зазвучала: пропустить и продолжить очередь. */
    private fun failPhrase(gen: Int, slot: Slot, reason: String) {
        advance(gen, slot, failure = reason)
    }

    /** Общий путь «фраза снята с очереди» для успеха и отказа плеера. */
    private fun advance(gen: Int, slot: Slot, failure: String?) {
        var chosen: List<Slot> = emptyList()
        var drained = false
        synchronized(lock) {
            if (released || generationCounter != gen) return
            if (slots[slot.seq] !== slot) return
            slots.remove(slot.seq)
            playing = false
            if (nextSeq <= slot.seq) nextSeq = slot.seq + 1
            chosen = collectStartsLocked()
            drained = slots.isEmpty() && inFlight == 0
        }
        if (failure != null) notifyPhraseFailed(slot.text, failure)
        launchSynthesis(chosen, gen)
        if (drained) notifyDrained() else pumpPlayback()
    }

    // --- внутреннее ----------------------------------------------------------------------------

    /** Убрать пропущенную фразу; порядок воспроизведения продолжается со следующей. */
    private fun dropLocked(slot: Slot) {
        slots.remove(slot.seq)
        if (nextSeq <= slot.seq) nextSeq = slot.seq + 1
    }

    /** Пропустить номера, которых в очереди уже нет (пропущенные фразы). */
    private fun skipMissingLocked() {
        if (slots.isEmpty()) return
        while (nextSeq <= seqCounter && slots[nextSeq] == null) nextSeq++
    }

    /** Очередь доиграна: сигнал хосту и подготовка счётчика «первого аудио» к новому ходу. */
    private fun notifyDrained() {
        synchronized(lock) {
            if (released) return
            firstAudioReported = false
        }
        runCatching { onDrained() }
    }

    private fun notifyPhraseFailed(text: String, reason: String) {
        // Метрика раньше колбэка: даже если хост упадёт в обработчике, счётчик пропусков верен.
        metrics?.onPhraseFailed(reason)
        runCatching { onPhraseFailed(text, reason) }
    }

    companion object {
        /** Одновременных запросов синтеза: больше не ускоряет последовательный звук. */
        const val DEFAULT_SYNTHESIS_PARALLELISM: Int = 2

        /** Синтезируем на одну фразу вперёд: пока играет N, готовится N+1. */
        const val DEFAULT_LOOKAHEAD: Int = 1

        private val EMPTY_BYTES = ByteArray(0)
    }
}
