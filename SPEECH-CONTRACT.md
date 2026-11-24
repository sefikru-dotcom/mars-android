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

## 5. PlusVibe — основной голос (18.11.2026)

Живые замеры: `plusvibe-tts-live-20261118.md` в корне workspace. Ниже — как это легло в код.

### 5.1 Маршрут платного синтеза: АСИНХРОННЫЙ

`POST {root}/api/media/generate` → `GET {root}/api/media/jobs/{id}` → скачивание `resultUrls[0]`.

- `root` — база провайдера без сегмента `/v1` (`PlusVibeMediaTtsClient.mediaRoot`);
- `POST` может блокироваться 4–32 с: `readTimeout 180 с`, `callTimeout 200 с`;
- `resultUrls` — публичные ссылки: ключ при скачивании НЕ отправляется;
- `max_price_rub` уходит всегда (предохранитель, отказ бесплатный — `422 media_max_price_exceeded`);
- текст режет клиент (`preparePrompt`, 4096 знаков по границе слова) — котировка длину не проверяет;
- отмена корутины = `Call.cancel()`: перебивание не ждёт шаг опроса.

Синхронный `POST /v1/audio/speech` (`OpenAiSpeechTtsClient`) оставлен ТОЛЬКО как эталон контракта
и подмена в тестах: в живом замере 3 из 10 вызовов вернули обрезанный фрагмент фразы.

### 5.2 Голоса

`ServerTtsVoices.VOICE_DIALOG` = `plusvibe-alice` (ОСНОВНОЙ, ElevenLabs turbo, ≈4,9 ₽/мин),
`natural-svetlana`, `natural-dmitry`, `natural-ava`, `plusvibe-onyx` (gpt-4o-mini-tts, ≈0,06 ₽/мин),
`local-irina`. `ServerTtsVoices.DEFAULT_VOICE` = `plusvibe-alice` — он звучит, когда `voiceId == null`.

Модель синтеза — поле профиля (`ServerTtsVoiceProfile.model`): голос `Alice` существует только
внутри `elevenlabs-v4-tts`, `onyx` — внутри `gpt-4o-mini-tts`.

### 5.3 Цепочка деградации (оба чата)

```
облако (PlusVibe → edge → /api/tts) → Piper (единый OfflineVoice) → платформа (объявленная подмена)
```

- `ServerTtsSpeechEngine(fallback = piper, lastResort = system, fallbackChannel = OFFLINE)`:
  Piper не звучит, пока модель не скачана — поэтому платформа остаётся последним резервом;
- `PreferOfflineEngine(offline = piper, fallback = serverTtsEngine)` в ОБОИХ чатах: выбран
  локальный голос — говорит Piper, выбран сетевой — облако, отказ облака → Piper;
- **единый Piper** — `ru.mars.mobile.tts.OfflineVoice` (один порт и один прогрев на процесс).
  Экраны создают движки с `releasePort = false` / `ownsOfflinePort = false` и НЕ освобождают
  порт в `onDestroy`: иначе закрытие одного чата гасило бы голос другого.

### 5.4 Подмена голоса — обязательное событие

`VoiceSubstitution(spoken, reason)` → `VoiceSubstitutionNotice.text(...)` → статусная строка:

| Прозвучало | Текст |
|---|---|
| `OFFLINE` | «Облачный голос не ответил — читаю локальным голосом (Piper)» |
| `CLOUD` | «Локальный голос не поднялся — читаю облачным голосом» |
| `SYSTEM` | «Облачный голос недоступен, локальной модели нет — читаю системным голосом» |

Серверный чат: `ChatActivity.showVoiceSubstitution` (движок + обёртка). Локальный чат:
`LocalVoiceController(onVoiceSubstituted=…)` → `LocalVoiceButton` → `presenter.setNotice`.

### 5.5 Какой профиль считается PlusVibe

Платный голос уходит НЕ на сервер Марса, поэтому адрес и ключ обязаны принадлежать одному
профилю PlusVibe — и именно НАСТРОЕННОМУ, а не активному.

- `PlusVibeTtsProfile.select(state)` — политика выбора: активный профиль, если он сам PlusVibe,
  иначе первый профиль PlusVibe в порядке хранения. Активный провайдер другого вендора
  (Yeta, прокси) на выбор не влияет;
- провайдер узнаётся по РЕГИСТРИРУЕМОМУ домену (`plusvibeapi.ru`, `api.plusvibe.ai`).
  Подстрока в пути (`https://yeta.example/plusvibe`) и поддомен чужого домена
  (`plusvibe.yeta.example`) PlusVibe не считаются — ключ туда не поедет;
- `PlusVibeTtsAccess` собирает пару «адрес + ключ» ОДНИМ чтением профиля, `PlusVibeTtsClient`
  присваивает её одним действием: ключ одного профиля не может уехать на адрес другого;
- профиля нет — `no_plusvibe_profile`, ключа нет — `no_api_key`. Оба случая возвращаются ДО
  сети, а не превращаются в запрос на первый попавшийся хост;
- отказ платного голоса объявляется человеку (`onPaidUnavailable` в обоих чатах): фразу
  прочитал нейро-резерв, и «Алиса» не звучала — это видно строкой статуса
  (`VoiceSubstitutionNotice.paidUnavailable`).

### 5.6 Незакрытые стыки

- потоковая очередь фраз (`SpeechQueue`) НЕ имеет Piper-резерва: отказ облака на этом пути
  пока пропускает фразу без звука и без события подмены;
- локального кеша аудио нет (`resultsExpireAt` = 5 суток): каждая реплика синтезируется заново;
- тембр ElevenLabs `Alice` проверен только обратным распознаванием, не на слух.
