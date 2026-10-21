package ru.mars.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.VoiceCloseResult
import ru.mars.network.model.VoiceGatewayError
import ru.mars.network.model.VoiceInterruptResult
import ru.mars.network.model.VoiceResult
import ru.mars.network.model.VoiceSession
import ru.mars.network.repository.VoiceGatewayClient

/**
 * Управляющий порт голосового шлюза: только POST-вызовы жизненного цикла.
 *
 * Отдельный интерфейс (а не прямое использование [VoiceGatewayClient]) нужен по двум причинам:
 * канал проверяется JVM-тестами без сети, и здесь физически НЕТ метода чтения потока событий —
 * в приложении остаётся ровно один источник транскрипта (существующий chat SSE), а не второй
 * конкурирующий поток `api/voice/stream`.
 */
interface VoiceControlGateway {

    suspend fun createSession(
        sessionId: String,
        credential: ConsumedDeviceCredential,
        baseUrl: String,
    ): VoiceResult<VoiceSession>

    suspend fun interrupt(
        voiceId: String,
        sessionId: String,
        credential: ConsumedDeviceCredential,
        baseUrl: String,
    ): VoiceResult<VoiceInterruptResult>

    suspend fun close(
        voiceId: String,
        sessionId: String,
        credential: ConsumedDeviceCredential,
        baseUrl: String,
    ): VoiceResult<VoiceCloseResult>
}

/** Боевой порт: тонкая обёртка над [VoiceGatewayClient] (транспорт, авторизация, таймауты — там). */
class GatewayVoiceControl(
    private val client: VoiceGatewayClient = VoiceGatewayClient(),
) : VoiceControlGateway {

    override suspend fun createSession(
        sessionId: String,
        credential: ConsumedDeviceCredential,
        baseUrl: String,
    ): VoiceResult<VoiceSession> = client.createSession(sessionId, credential, baseUrl)

    override suspend fun interrupt(
        voiceId: String,
        sessionId: String,
        credential: ConsumedDeviceCredential,
        baseUrl: String,
    ): VoiceResult<VoiceInterruptResult> = client.interrupt(voiceId, sessionId, credential, baseUrl)

    override suspend fun close(
        voiceId: String,
        sessionId: String,
        credential: ConsumedDeviceCredential,
        baseUrl: String,
    ): VoiceResult<VoiceCloseResult> = client.close(voiceId, sessionId, credential, baseUrl)
}

/**
 * Область жизни для вызовов, которые ОБЯЗАНЫ пережить экран.
 *
 * `lifecycleScope` для `api/voice/close` не годится: экран уничтожается, scope отменяется
 * сразу после `onDestroy`, и запущенный там запрос до сервера не доходит — `voice_id`
 * остаётся висеть до TTL и занимает слот потока. Здесь нет ссылки на Activity: живут
 * только сами короткие POST-запросы закрытия, после чего в scope не остаётся работы.
 */
val VoiceTeardownScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Жизненный цикл серверного голосового канала для перебивания: `voice_id` создаётся по
 * фактическому API (`POST api/voice/session`), переиспользуется на сессию диалога и
 * закрывается (`POST api/voice/close`), когда разговор закончился.
 *
 * ## Зачем это вообще нужно
 * Локальная тишина при перебивании — только половина дела: сервер продолжает синтез и рассылает
 * `speech.stop` своим подписчикам. `POST api/voice/interrupt` — единственный способ сказать
 * серверу «замолчи», и он требует `voice_id`, который выдаёт сервер. Придумать его нельзя:
 * чужой/несуществующий id вернёт 404, а канал на сервере останется жить до TTL.
 *
 * ## Что здесь СОЗНАТЕЛЬНО не делается
 * - **Не читается `api/voice/stream`.** Транскрипт и дельты ответа приходят по chat SSE; второй
 *   поток событий дал бы два конкурирующих источника правды об одном ходе (и сервер отдал бы
 *   409 на второго подписчика).
 * - **Не отменяется ход.** Перебивание — steer, а не cancel: `/api/chat/cancel` здесь нет.
 * - **Не создаётся фиктивный id.** Пока сервер не ответил `voice_id`, никакого запроса с
 *   выдуманным идентификатором не уходит; перебивание остаётся локальным (тишина уже наступила).
 *
 * ## Ошибки
 * Все сетевые исходы глушатся: перебивание не должно показывать ошибку поверх разговора.
 * 404 на `interrupt` означает «канал протух/закрыт» — привязка сбрасывается, и следующее
 * перебивание создаст новый канал. 429/5xx — не повод рвать привязку: `voice_id` жив.
 *
 * @param gateway управляющие вызовы шлюза.
 * @param credentialProvider текущий device-token (может отсутствовать — тогда канал не открыть).
 * @param scope область для фоновых POST-вызовов. Для `close()`/`bindSession()` это должна быть
 *   область, переживающая экран ([VoiceTeardownScope]): иначе закрытие канала отменяется
 *   вместе с Activity. Диспетчер берётся из самой области — класс не навязывает свой.
 * @param baseUrl адрес бэкенда.
 */
class VoiceInterruptChannel(
    private val gateway: VoiceControlGateway,
    private val credentialProvider: () -> ConsumedDeviceCredential?,
    private val scope: CoroutineScope,
    private val baseUrl: String,
) {

    /** Открытый канал: `voice_id` вместе с сессией, к которой он привязан. */
    data class Binding(val voiceId: String, val sessionId: String)

    /** Текущая привязка; `null` — канала нет (ещё не создавали, закрыли или он протух). */
    @Volatile
    var binding: Binding? = null
        private set

    /** Диагностика: сколько каналов открыто (без текста и без id — только числа). */
    @Volatile
    var createdCount: Int = 0
        private set

    /** Диагностика: сколько перебиваний сервер подтвердил. */
    @Volatile
    var interruptedCount: Int = 0
        private set

    /** Диагностика: сколько перебиваний сервер уже видел (дебаунс 2 с) — не ошибка. */
    @Volatile
    var alreadyInterruptedCount: Int = 0
        private set

    /** Диагностика: сколько вызовов не дали эффекта (нет сессии/токена, отказ сети, 4xx). */
    @Volatile
    var failedCount: Int = 0
        private set

    /** Диагностика: сколько каналов закрыто. */
    @Volatile
    var closedCount: Int = 0
        private set

    /** Один критический участок на создание канала: два перебивания подряд не откроют два. */
    private val mutex = Mutex()

    /**
     * Перебивание: best-effort `POST api/voice/interrupt` для текущей сессии.
     *
     * Возврат сразу: локальная тишина уже наступила, сеть догоняет в фоне. Ход НЕ отменяется.
     */
    fun interrupt(sessionId: String?) {
        val sid = sessionId?.trim().orEmpty()
        if (sid.isEmpty()) {
            failedCount++
            return
        }
        scope.launch {
            runCatching { interruptNow(sid) }
        }
    }

    /**
     * То же перебивание, но дожидаясь исхода — для тестов и для мест, где хост хочет знать
     * результат (диагностика). Ошибки не бросает: исход виден по счётчикам.
     */
    suspend fun interruptNow(sessionId: String): Boolean {
        // Пустая сессия — не сессия: `POST api/voice/session` с пустым телом вернул бы 400,
        // а канал на сервере не открылся бы. Проверяем ДО сети, ровно как в [interrupt].
        if (sessionId.isBlank()) {
            failedCount++
            return false
        }
        val sid = sessionId.trim()
        val voiceId = ensureBinding(sid) ?: return false
        val credential = credentialProvider() ?: run {
            failedCount++
            return false
        }
        return when (val result = gateway.interrupt(voiceId, sid, credential, baseUrl)) {
            is VoiceResult.Success -> {
                if (result.value.alreadyInterrupted) alreadyInterruptedCount++ else interruptedCount++
                true
            }
            is VoiceResult.Failure -> {
                failedCount++
                // Канал на сервере больше не знает этот voice_id: привязку снимаем, чтобы
                // следующее перебивание открыло новый, а не долбилось в мёртвый.
                if ((result.error as? VoiceGatewayError.Http)?.code == HTTP_NOT_FOUND) {
                    synchronized(this) { binding = null }
                }
                false
            }
        }
    }

    /**
     * Сменить сессию диалога: старый канал закрывается, новый откроется по первому перебиванию.
     * Зовётся при переключении/создании диалога — `voice_id` одного диалога бессмыслен в другом.
     */
    fun bindSession(sessionId: String?) {
        val sid = sessionId?.trim().orEmpty()
        val current = binding
        if (current == null) return
        if (sid.isNotEmpty() && current.sessionId == sid) return
        binding = null
        closeBindingAsync(current)
    }

    /**
     * Закрыть канал: конец голосового режима/уничтожение экрана. Идемпотентно.
     *
     * После закрытия `voice_id` непригоден — сервер это и ожидает (`closed: true`), иначе
     * канал висел бы до TTL и занимал слот потока.
     */
    fun close() {
        val current = binding ?: return
        binding = null
        closeBindingAsync(current)
    }

    /** Открыть канал, если его нет, и вернуть `voice_id`. `null` — открыть не удалось. */
    private suspend fun ensureBinding(sessionId: String): String? = mutex.withLock {
        val current = binding
        if (current != null && current.sessionId == sessionId) return@withLock current.voiceId
        if (current != null) {
            // Другой диалог: старый канал закрываем (иначе он живёт до TTL).
            binding = null
            closeBinding(current)
        }
        val credential = credentialProvider() ?: run {
            failedCount++
            return@withLock null
        }
        when (val result = gateway.createSession(sessionId, credential, baseUrl)) {
            is VoiceResult.Success -> {
                val voiceId = result.value.voiceId
                if (voiceId.isBlank()) {
                    // Пустой voice_id — это не идентификатор: с ним нельзя ни прервать, ни закрыть.
                    failedCount++
                    return@withLock null
                }
                binding = Binding(voiceId, result.value.sessionId.ifBlank { sessionId })
                createdCount++
                voiceId
            }
            is VoiceResult.Failure -> {
                failedCount++
                null
            }
        }
    }

    private fun closeBindingAsync(target: Binding) {
        // Диспетчер области, а не зашитый IO: тесты подменяют область на Unconfined и видят
        // фактический порядок «сначала закрыли старый, потом открыли новый».
        scope.launch {
            runCatching { closeBinding(target) }
        }
    }

    private suspend fun closeBinding(target: Binding) {
        val credential = credentialProvider() ?: run {
            failedCount++
            return
        }
        when (gateway.close(target.voiceId, target.sessionId, credential, baseUrl)) {
            is VoiceResult.Success -> closedCount++
            is VoiceResult.Failure -> failedCount++
        }
    }

    private companion object {
        /** `404` — сессия/канал неизвестен серверу: привязка недействительна. */
        const val HTTP_NOT_FOUND = 404
    }
}
