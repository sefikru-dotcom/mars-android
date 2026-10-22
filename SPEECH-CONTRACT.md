# SPEECH-CONTRACT — Z3 (ветка `upgrade/voice-speech-2110`)

Для Z4 (UI/wiring) и Z2 (barge-in). Всё ниже — **фактические сигнатуры этой ветки**.

## 1. Сетевые контракты (core-network)

### 1.1 `POST /api/tts` — тело запроса

```json
{"text":"...","engine":"edge","voice":"ru-RU-SvetlanaNeural","rate":"+10%"}
```

- `engine`/`voice` едут **только если заданы явно** (null → ключа нет, сервер решает сам).
- `rate`/`pitch` валидируются: мусор не попадает в тело (`ServerTtsContract.prosodyOrNull`).
- Сборка тела: `ServerTtsContract.buildBody(request: ServerTtsSpeechRequest): String`
  (старая перегрузка `buildBody(text, rate, pitch, engine, voice)` сохранена).

```kotlin
data class ServerTtsSpeechRequest(
    val text: String,
    val engine: String? = null,
    val voice: String? = null,
    val rate: String? = null,
    val pitch: String? = null,
) {
    companion object { fun of(text: String, profile: ServerTtsVoiceProfile?): ServerTtsSpeechRequest }
}

data class ServerTtsVoiceProfile(
    val id: String, val engine: String, val voice: String,
    val rate: String? = null, val pitch: String? = null,
    val natural: Boolean = false, val needsNetwork: Boolean = true,
) {
    val isLocal: Boolean
    val logTag: String
}

object ServerTtsVoices {
    val NATURAL_SVETLANA: ServerTtsVoiceProfile   // edge / ru-RU-SvetlanaNeural / +10%
    val LOCAL_IRINA: ServerTtsVoiceProfile        // piper / ru_RU-irina-medium
    val VOICE_DIALOG: List<ServerTtsVoiceProfile> // порядок: Svetlana → irina (резерв)
    fun byId(id: String?): ServerTtsVoiceProfile?
}
```

### 1.2 `POST /api/tts` — ответы и повтор

```kotlin
sealed class ServerTtsResult {
    class Audio(val bytes: ByteArray, val contentType: String) : ServerTtsResult
    class Unavailable(
        val reason: String,              // rate_limited | unauthorized | not_found | server_error | invalid_response | transport
        val httpCode: Int? = null,
        val retryAfterMs: Long? = null,  // разобранный Retry-After (уже в пределах maxDelayMs)
    ) : ServerTtsResult
}

class ServerTtsRetryPolicy(
    val maxAttempts: Int = 2,
    val maxDelayMs: Long = 2_500,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    fun isRetryable(reason: String): Boolean                 // rate_limited | server_error
    fun delayBeforeRetryMs(attempt: Int, retryAfterMs: Long?): Long
    suspend fun awaitRetry(attempt: Int, retryAfterMs: Long?)
    companion object { fun parseRetryAfterMs(header: String?, nowMs: Long): Long? }
}

class ServerTtsRepository(
    client: OkHttpClient = HttpClients.rest,
    retryPolicy: ServerTtsRetryPolicy = ServerTtsRetryPolicy(),
    nowMs: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun synthesize(baseUrl: String, credential: ConsumedDeviceCredential,
                           request: ServerTtsSpeechRequest): ServerTtsResult
    suspend fun synthesize(baseUrl: String, credential: ConsumedDeviceCredential, text: String,
                           rate: String? = null, pitch: String? = null,
                           engine: String? = null, voice: String? = null): ServerTtsResult
}
```

Повтор: **один** (2 попытки), пауза из `Retry-After` (секунды или HTTP-дата), потолок 2.5 с,
отмена корутины рвёт ожидание. `401/404/400` не повторяются.

### 1.3 `POST /api/chat/start` — `voice_mode`

```kotlin
ChatRepository.sendMessage(
    endpoint, credential, message,
    sessionId = null, attachments = emptyList(), model = null, modelProvider = null,
    explicitModelPick = false, workspace = null,
    onTodoState = null, onSideEvent = null, onEventId = null,
    voiceMode: Boolean = false,   // ← новое, строго opt-in
): Flow<ChatEvent>
```

`voiceMode = true` → в теле `"voice_mode":true` (строгий bool). `false` → **ключа нет вовсе**,
обычный чат побайтово прежний. Эхо `voice_mode:true` в ответе `/chat/start` клиент не парсит
(контракт ответа не расширялся) — статус читается из `session.ready` (1.4).

### 1.4 `session.ready` — проективные поля

```kotlin
data class SessionReady(
    ... существующие ...,
    val voiceMode: Boolean? = null,        // null = сервер не сообщил (старый сервер)
    val capabilities: List<String> = emptyList(),
) {
    val supportsVoiceMode: Boolean             // voiceMode == true
    fun supports(capability: String): Boolean  // без регистра
}
```

Отсутствие ключей — норма, не ошибка.

## 2. Речевой слой (app-mobile)

### 2.1 `StreamingSpeechController` — API хоста

```kotlin
class StreamingSpeechController(
    chunker: SentenceChunker = SentenceChunker(),
    normalizer: VoiceNormalizer = VoiceNormalizer(),
    queue: SpeechQueue,
    val metrics: SpeechLatencyMetrics = SpeechLatencyMetrics(),
) {
    fun onDelta(delta: String)          // каждая дельта стрима
    fun onTurnFinished()                // конец хода; идемпотентно
    fun speakWhole(text: String)        // ручная озвучка готового сообщения
    fun stop()                          // barge-in: тишина + сброс накопленного
    fun release()
    fun latencySnapshot(): SpeechLatencySnapshot
    val isSpeaking: Boolean

    companion object {
        fun forAndroid(
            context: Context,
            client: ServerTtsClient,
            scope: CoroutineScope,
            onFirstAudio: () -> Unit = {},
            onDrained: () -> Unit = {},
            onPhraseFailed: (text: String, reason: String) -> Unit = { _, _ -> },
            metrics: SpeechLatencyMetrics = SpeechLatencyMetrics(),
            retryPolicy: SpeechRetryPolicy = SpeechRetryPolicy(),
        ): StreamingSpeechController
    }
}
```

Гарантии (покрыты тестами):
- первая фраза уходит в синтез на первой границе предложения; без пунктуации — bounded chunk по `maxPhraseLength`;
- `onTurnFinished()` идемпотентен: повторный вызов не произносит финал дважды; новая дельта открывает новый ход;
- после `stop()` поздний ответ синтеза **не звучит** (защита поколением);
- синтез N+1 идёт, пока играет N (lookahead = 1, параллелизм = 2).

### 2.2 `ServerTtsClient` / мост

```kotlin
fun interface ServerTtsClient { suspend fun synthesize(text: String): ServerTtsResult }

fun interface ServerTtsTransport {
    suspend fun synthesize(baseUrl: String, credential: ConsumedDeviceCredential,
                           request: ServerTtsSpeechRequest): ServerTtsResult
}

class RepositoryServerTtsClient(
    baseUrl: String,
    credentialProvider: () -> ConsumedDeviceCredential?,
    selector: SpeechVoiceProfileSelector? = SpeechVoiceProfileSelector(),
    transport: ServerTtsTransport = ...,   // по умолчанию ServerTtsRepository
) : ServerTtsClient {
    var lastProfileId: String?   // диагностика, без текста
}
```

**Изменилось для UI:** `ServerTtsTransport` принимает `ServerTtsSpeechRequest` (было 5 отдельных
аргументов), у `RepositoryServerTtsClient` больше нет `rate`/`pitch` — голос задаётся селектором.

### 2.3 Выбор голоса

```kotlin
class SpeechVoiceProfileSelector(
    private val profiles: List<ServerTtsVoiceProfile> = ServerTtsVoices.VOICE_DIALOG,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val transientCooldownMs: Long = 45_000,
    private val permanentCooldownMs: Long = 600_000,
    localVoiceInstalled: Boolean = true,
) {
    fun candidates(): List<ServerTtsVoiceProfile>
    fun current(): ServerTtsVoiceProfile?
    fun setLocalVoiceInstalled(installed: Boolean)
    fun failureCount(profile: ServerTtsVoiceProfile): Int
    fun onAudio(profile: ServerTtsVoiceProfile)
    fun onUnavailable(profile: ServerTtsVoiceProfile, result: ServerTtsResult.Unavailable): Boolean
    fun reset()
}
```

Политика: Svetlana (естественный) **всегда первая**; irina — резерв, включается только по факту
отказа голоса (нет сети / `404` / `400` / `501`). `429` и `401` профиль **не** понижают — это
лимитер и токен. Успех снимает понижение (возврат к Svetlana). `localVoiceInstalled=false` →
irina не предлагается вовсе (системный TTS вместо навязанного piper).

### 2.4 Метрики задержки

```kotlin
class SpeechLatencyMetrics(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    fun onFirstDelta()
    fun onSynthesisRequest()
    fun onAudioStart()                        // РЕАЛЬНЫЙ старт воспроизведения
    fun onSynthesisRetry(reason: String)
    fun onPhraseFailed(reason: String)
    fun snapshot(): SpeechLatencySnapshot
    fun reset()
}

data class SpeechLatencySnapshot(
    val firstDeltaToRequestMs: Long? = null,
    val firstDeltaToAudioMs: Long? = null,
    val requestToAudioMs: Long? = null,
    val synthesisRequests: Int = 0,
    val synthesisRetries: Int = 0,
    val phraseFailures: Int = 0,
    val rateLimited: Int = 0,
) {
    fun logLine(): String   // "speech delta->tts=… tts->audio=… delta->audio=… requests=… retries=… failed=… limited=…"
}
```

Только числа и короткие причины: **ни текста, ни токенов, ни URL**. `firstDeltaToAudioMs` заполняется
исключительно по факту старта плеера — не по приходу текста.

### 2.5 Повтор фразы в очереди

```kotlin
class SpeechRetryPolicy(
    val maxAttempts: Int = 2,
    val maxDelayMs: Long = 2_500,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    fun isRetryable(reason: String): Boolean
    fun delayBeforeRetryMs(attempt: Int, retryAfterMs: Long?): Long
    suspend fun awaitRetry(attempt: Int, retryAfterMs: Long?)
}

class SpeechQueue(
    client: ServerTtsClient, playback: ServerTtsPlayback, scope: CoroutineScope,
    onFirstAudio: () -> Unit = {}, onDrained: () -> Unit = {},
    onPhraseFailed: (text: String, reason: String) -> Unit = { _, _ -> },
    maxSynthesisParallelism: Int = 2, lookahead: Int = 1,
    retryPolicy: SpeechRetryPolicy = SpeechRetryPolicy(),
    metrics: SpeechLatencyMetrics? = null,
)
```

Повтор прерывается, если фраза перестала быть актуальной (проверка до и после паузы) — после
`stopAll()` поздний звук не появляется.

### 2.6 `VoiceNormalizer` — литеральные слэши

`normalize()` теперь чистит литеральный `/` и `\` **контекстно**:
`и/или` → «или»; `api/tts` → два слова; одиночный/двойной слэш → пауза. Имя «Слэш» не
переписывается (словарной замены нет — она ломает Svetlana и не помогает irina).

## 3. Что нужно от Z4

1. `streamingSpeech = StreamingSpeechController.forAndroid(...)` — прежняя сигнатура, добавлены
   необязательные `metrics` и `retryPolicy`.
2. `ChatRepository.sendMessage(..., voiceMode = true)` — только для ходов, начатых голосом.
3. `ServerTtsSpeechEngine` и `VoiceOutputController` публичный API не меняли.

## 4. Открытые серверные вопросы (клиент не блокируют)

- лимитер `api/tts` 2 с заменяется серверным продолжением; клиент уже терпит `429` одним повтором;
- общий серверный нормализатор и `voice_mode` в `session.ready` читаются терпимо: появление
  ключей не требует выпуска клиента.
