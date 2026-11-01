# КОНТРАКТ ВОЛНЫ 1 — «Прямой провайдер + локальный чат + инструменты телефона»

Репозиторий: `C:\Users\SKY\.zcode\workspace\default\mars-android`
Ветка: `wave/direct-provider`
База: `origin/main` (`8060aff`). Собрано/проверено координатором до старта.

Этот файл — единственный источник истины по именам, пакетам и сигнатурам.
Расхождения с ним записывать в отчёт, а не править молча.

---

## 0. Что строим и чего НЕ строим

**Строим (волна 1):** нативный локальный чат, который обращается к OpenAI-совместимому
провайдеру напрямую (без сервера Марса), с собственным агентным циклом и инструментами
телефона. Отдельный раздел левого бара «Локально» — рядом с серверным «Чат».

**НЕ строим в волне 1:** офлайн-синтез речи, фоновый голос при заблокированном экране,
мост «сервер → инструменты телефона». Это волны 2 и 3. Не закладывать под них код,
кроме заранее описанных швов (§7).

---

## 1. Жёсткие запреты для всех воркеров

1. **Не запускать Gradle. Никогда, никакими командами.** Сборку делает только координатор.
2. **Не править:** `build.gradle.kts` (корневой и модулей), `settings.gradle.kts`,
   `gradle/wrapper/*`, `local.properties`, `AndroidManifest.xml`, файлы в `res/`,
   `proguard-rules.pro`. Нужна зависимость или ресурс — пиши в отчёт «требуется координатору».
3. **Не править чужие каталоги** (§4 — таблица владения).
4. **Не создавать общие типы у себя.** Типы из §3 создаёт координатор; используй их как есть.
5. **Не ходить в git**: ни commit, ни push, ни checkout, ни stash. Только правка файлов.
6. **Не выдумывать успешный прогон.** Что не проверял — так и пиши.

Технические факты, чтобы не тратить время:

- Проект на **классических View** (`android.view.*`), не Compose. Room нет.
- **Kotlin 1.9.22, AGP 8.2.2, Gradle 8.2, compileSdk 34, minSdk 28, JVM target 1.8.**
  Более новые языковые конструкции (`data object`, `enum.entries` можно, context receivers — нет)
  проверяй по компилятору 1.9. Тип `data object` в 1.9 уже есть.
- Строки UI — русские, прямо в коде экранов (в `res/values/strings.xml` лезут только владельцы `res/`).
- `org.json` доступен в app-mobile (и в `testImplementation`), Moshi — в core-network.
- В `app-mobile` уже есть okhttp (`implementation(project(":core-network"))` даёт `api okhttp`).
- Тесты JVM лежат в `app-mobile/src/test/java/ru/mars/mobile/`, JUnit4.
  Маскированные значения (`***`) в выводе инструментов — это редакция секретов, а не порча файла.

---

## 2. Раскладка по воркерам и файлам

| Поток | Владеет (создаёт/правит) | Не трогает |
|---|---|---|
| **W1 — Локальное ядро** | `core-network/src/main/java/ru/mars/network/local/**` (новый каталог целиком) | всё остальное |
| **W2 — Инструменты телефона** | `app-mobile/src/main/java/ru/mars/mobile/localtools/**` (новый каталог целиком) | всё остальное |
| **W3 — Экран локального чата** | `app-mobile/src/main/java/ru/mars/mobile/LocalChatActivity.kt`, `LocalChat*.kt`, `LocalSessions*.kt`, `LocalProviderSettings*.kt`, `LocalChatScreen.kt` (все — новые файлы в `ru/mars/mobile/`) | навигацию (`AppNavIntents`, `AppDestination`, `AppSectionsPolicy`), `AndroidManifest.xml`, `ChatActivity*`, `MarsTheme.kt` |
| **W4 — Тесты и политика** | `app-mobile/src/test/java/ru/mars/mobile/local*/**`, `core-network/src/test/java/ru/mars/network/local/**` | всё остальное |
| **Координатор** | `AppDestination`, `AppSectionsPolicy`, `AppNavIntents`, `AndroidManifest.xml`, `res/*`, `build.gradle.kts`, привязка экрана в `ChatSessionsActivity`/`AppSectionsBar` | — |

Перед стартом координатор создаёт в `core-network` и `app-mobile` **пустые каталоги и
файл-заглушку** там, где нужно, чтобы воркеры не спорили за один файл.

---

## 3. Общие типы (создаёт координатор, менять нельзя)

### 3.1 `core-network/.../local/LocalProvider.kt`

```kotlin
package ru.mars.network.local

/** Профиль прямого провайдера. Ключ НЕ здесь: он живёт в KeyStore-хранилище. */
data class LocalProviderProfile(
    val id: String,
    val title: String,
    val baseUrl: String,          // напр. "https://plusvibeapi.ru/v1" — без слэша на конце
    val model: String,            // напр. "claude-haiku-5.5"
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val reasoningEffort: String? = null,
)

/** Состояние профилей. activeId == null — провайдер не настроен. */
data class LocalProviderState(
    val profiles: List<LocalProviderProfile> = emptyList(),
    val activeId: String? = null,
) {
    val active: LocalProviderProfile? get() = profiles.firstOrNull { it.id == activeId }
}

interface LocalProviderStore {
    fun state(): LocalProviderState
    fun saveProfile(profile: LocalProviderProfile)
    fun removeProfile(id: String)
    fun setActive(id: String?)
    /** Ключ API: отдельное хранилище, в SharedPreferences не попадает. */
    fun apiKey(profileId: String): String?
    fun saveApiKey(profileId: String, key: String)
    fun clearApiKey(profileId: String)
}

/** Нормализация адреса: схема, без хвостового слэша, https по умолчанию. */
object LocalProviderUrls {
    fun normalizeBaseUrl(raw: String): String
    /** "<base>/chat/completions" — единственное место, где строится путь. */
    fun chatCompletionsUrl(baseUrl: String): String
    fun modelsUrl(baseUrl: String): String
}
```

### 3.2 `core-network/.../local/LocalChatModels.kt`

```kotlin
package ru.mars.network.local

enum class LocalRole { SYSTEM, USER, ASSISTANT, TOOL }

/** Сообщение локального диалога. toolCalls — только у ASSISTANT. */
data class LocalMessage(
    val id: String,
    val role: LocalRole,
    val text: String = "",
    val toolCalls: List<LocalToolCall> = emptyList(),
    val toolCallId: String? = null,   // у TOOL: на какой вызов отвечаем
    val createdAt: Long = 0L,
)

data class LocalToolCall(val id: String, val name: String, val argumentsJson: String)

/** Описание инструмента для провайдера (OpenAI function schema). */
data class LocalToolSchema(val name: String, val description: String, val parametersJson: String)

/** События одного хода. Ровно эти варианты; новые — только через координатора. */
sealed interface LocalChatEvent {
    data class Delta(val text: String) : LocalChatEvent
    data class ReasoningDelta(val text: String) : LocalChatEvent
    data class ToolRequested(val call: LocalToolCall) : LocalChatEvent
    data class ToolFinished(val callId: String, val ok: Boolean, val preview: String) : LocalChatEvent
    data class AssistantFinished(val fullText: String, val toolCalls: List<LocalToolCall>) : LocalChatEvent
    data class Error(val message: String, val retryable: Boolean = false) : LocalChatEvent
}

/** Сессия локального чата (метаданные списка). */
data class LocalSession(val id: String, val title: String, val updatedAt: Long)
```

### 3.3 `core-network/.../local/LocalChatClient.kt`

```kotlin
package ru.mars.network.local

/**
 * Один шаг диалога: отправить историю провайдеру и отдать стрим событий.
 * Ключ передаётся параметром (не хранится в клиенте).
 */
interface LocalChatClient {
    fun stream(
        profile: LocalProviderProfile,
        apiKey: String,
        messages: List<LocalMessage>,
        tools: List<LocalToolSchema> = emptyList(),
    ): Flow<LocalChatEvent>

    /** GET {base}/models — проверка ключа и адреса. Пустой список при отказе. */
    suspend fun models(profile: LocalProviderProfile, apiKey: String): Result<List<String>>
}
```

### 3.4 `core-network/.../local/LocalAgentLoop.kt`

```kotlin
package ru.mars.network.local

/** Исполнение инструмента на устройстве. Возвращает текст результата для модели. */
fun interface LocalToolExecutor {
    suspend fun execute(call: LocalToolCall): LocalToolResult
}

data class LocalToolResult(val ok: Boolean, val content: String, val preview: String = content.take(200))

/**
 * Агентный цикл: stream → tool_calls → execute → снова stream, пока модель не
 * ответит текстом или не кончится лимит итераций.
 * Гарантии: не бросает наружу; каждый шаг отдаёт событие; лимит итераций —
 * константа в компаньоне, не параметр UI.
 */
class LocalAgentLoop(
    private val client: LocalChatClient,
    private val executor: LocalToolExecutor,
    private val maxIterations: Int = MAX_ITERATIONS,
) {
    fun run(
        profile: LocalProviderProfile,
        apiKey: String,
        history: List<LocalMessage>,
        tools: List<LocalToolSchema>,
    ): Flow<LocalChatEvent>

    companion object { const val MAX_ITERATIONS = 8 }
}
```

---

## 4. Задания

### W1 — Локальное ядро (`core-network/.../local/`)

Реализовать **ровно** контракты §3.1–3.4 и ничего больше в этом каталоге.

Обязательно:

1. `LocalProviderUrls.normalizeBaseUrl("plusvibeapi.ru/v1/")` → `https://plusvibeapi.ru/v1`.
   Пустая строка → пустая строка (не «https://»). Уже с `http://` — сохранить схему.
2. `OkHttpLocalChatClient` на okhttp 4.12 (без новых зависимостей, без Retrofit):
   `POST {base}/chat/completions`, тело вручную (`org.json` нельзя — это core-network,
   там Moshi; можно `StringBuilder`), заголовки `Authorization: Bearer <key>`,
   `Content-Type: application/json`, `Accept: text/event-stream`, `stream: true`.
3. SSE-парсер **свой**, не переиспользовать серверный `SseChunkParser` (у него другой контракт).
   Ловушки, проверенные живым ответом PlusVibe:
   - `tool_calls` приходят чанками: `id` и `name` только в первом, далее `index` + куски
     `arguments`, которые **конкатенируются по index**, а не перезаписываются;
   - стрим может закончиться **без `data: [DONE]`** — EOF после `finish_reason` не ошибка;
   - `finish_reason` приходит в чанке с пустым `delta: {}`.
4. Таймауты: connect 15 с, read 180 с, общий watchdog молчания 180 с
   (мс, не наносекунды — этот баг уже ловили).
5. Ретрай **только до первого полученного токена**: 429/5xx/IOException, максимум 2 попытки,
   пауза 1 с и 3 с. После первого токена — ошибка, не ретрай.
6. `models()` — `GET {base}/models`, `data[].id`; 401 → `Result.failure` с текстом
   «ключ не принят», не пустой список.
7. `LocalAgentLoop`: лимит 8 итераций; при превышении — `Error("слишком много шагов")`
   и завершение; `TOOL`-сообщения добавляются в историю с `toolCallId`; пустой `argumentsJson`
   трактовать как `{}`. Никаких исключений наружу: всё в `LocalChatEvent.Error`.
8. Чистый JVM: **ни одного импорта `android.*`** в этом каталоге (иначе тесты W4 не соберутся).

### W2 — Инструменты телефона (`app-mobile/.../localtools/`)

Портировать из Венеры (`C:\Users\SKY\.zcode\workspace\default\venus`) **только рабочие**
инструменты, адаптировав к Марсу. Обязательный минимум (7 штук):

`set_alarm`, `set_timer`, `find_contact`, `create_event`, `list_events`,
`read_clipboard`, `write_clipboard`, `get_battery`, `get_device_info`.

Правила:

1. Контракт инструмента — **свой, локальный для Марса**, не тащить `app.venus.*`:
   ```kotlin
   package ru.mars.mobile.localtools
   interface LocalDeviceTool {
       val schema: ru.mars.network.local.LocalToolSchema
       /** Всегда true для деструктивных: будильник, запись буфера, событие календаря. */
       val requiresConfirmation: Boolean
       suspend fun execute(argumentsJson: String): ru.mars.network.local.LocalToolResult
   }
   ```
2. `object LocalToolCatalog { fun tools(context: Context): List<LocalDeviceTool> }` —
   единственная точка сборки списка. **Порядок стабильный** (он влияет на кэш провайдера).
3. Разрешения проверять через `ContextCompat.checkSelfPermission`; нет разрешения —
   `LocalToolResult(ok = false, content = "нет разрешения READ_CONTACTS")`, **не** исключение
   и **не** системный диалог из инструмента.
4. Ничего не выполнять в фоне: инструменты короткие, календарь/контакты — через ContentResolver.
5. Из Венеры НЕ брать: `take_photo` (требует живую Activity), `read_notifications`
   (нужен NotificationListenerService — отдельное разрешение и экран), SSH, файловые
   инструменты, `open_app`/`media_control`/`set_torch`/громкость
   (в Венере они **не зарегистрированы**, то есть не проверены — оставляем на волну 2).
6. Строки аргументов — русские, описание схемы — по-русски, компактно.

### W3 — Экран локального чата (View, не Compose)

Файлы: `LocalChatActivity.kt`, `LocalSessionsActivity.kt`, `LocalProviderSettingsActivity.kt`
плюс вспомогательные `LocalChat*.kt`. Все — **новые** файлы в `ru/mars/mobile/`.

Обязательно:

1. Три экрана по образцу существующих: `SectionScaffold.create(...)` для шапки,
   палитра только через `MarsTheme.*` (никаких своих цветов), safe-area через shell.
2. Список локальных диалогов → экран диалога → композер с отправкой и стопом.
3. Стрим отображать инкрементально; кнопка «Стоп» отменяет текущий ход.
4. Карточка инструмента в ленте: имя + результат (`preview`), свёрнутая.
5. **Подтверждение перед деструктивным инструментом**: диалог `AlertDialog` в стиле
   `MarsDialog`, кнопки «Разрешить» / «Отклонить». Таймаут 120 с → «Отклонено».
6. Экран настроек провайдера: список профилей, поля baseUrl / model / ключ,
   кнопка «Проверить» (зовёт `models()`), сохранение в `LocalProviderStore`.
   Ключ после сохранения показывать как `••••` и не выводить в лог.
7. **Навигация не твоя.** Никаких правок `AppDestination`, `AppNavIntents`,
   `AndroidManifest.xml`, `AppSectionsBar`. Все внешние зависимости — необязательными
   параметрами со значениями по умолчанию:
   ```kotlin
   class LocalChatActivity : ComponentActivity() {
       companion object {
           const val EXTRA_SESSION_ID = "local_session_id"
           fun newIntent(context: Context, sessionId: String? = null): Intent
       }
   }
   ```
8. Хранилище диалогов — **простое и файловое** (JSON в `filesDir/local-chat/`), без Room:
   его в проекте нет, а тащить KSP ради волны 1 нельзя. Формат — твой, но:
   атомарная запись (`.tmp` + rename), лимит 200 сообщений на диалог, лимит 100 диалогов.
9. Если чего-то не хватает (иконка, строка в `strings.xml`, зависимость) — пиши в отчёт,
   не правь `res/`.

### W4 — Тесты и политика

Только тесты JVM (`src/test`), без Android-зависимостей. Обязательный минимум:

**core-network:**
- `LocalProviderUrlsTest`: нормализация (6 случаев, включая пустую строку и уже `http://`).
- `LocalSseParserTest`: чанки `tool_calls` склеиваются по index; EOF без `[DONE]`;
  `finish_reason` в пустой дельте; битый JSON не роняет поток.
- `LocalAgentLoopTest`: 0 инструментов → один шаг; 1 инструмент → два шага и `ToolFinished`;
  лимит итераций обрывает цикл; ошибка клиента → `Error`, без исключения.
- `LocalRetryPolicyTest`: ретрай до первого токена, отсутствие ретрая после.

**app-mobile:**
- `LocalToolCatalogTest`: ровно ожидаемый набор имён, порядок стабилен,
  у деструктивных `requiresConfirmation == true`.
- `LocalSessionStoreTest`: обрезка до 200 сообщений, атомарная запись, битый файл не роняет.
- `LocalChatPresenterTest`: дельта → текст растёт; `ToolRequested` деструктивного инструмента
  не исполняется без подтверждения; отмена останавливает ход.

Тесты писать против **контрактов §3**; если реализация W1/W3 ещё не готова — тесты должны
компилироваться по интерфейсам (fakes), а не ждать чужих классов.

---

## 5. Критерии приёмки волны (проверяет координатор)

1. `:core-network:testDebugUnitTest` и `:app-mobile:testDebugUnitTest` зелёные.
2. `:app-mobile:assembleDebug` собирается; `aapt2 dump badging` даёт `ru.mars.mobile`.
3. Раздел «Локально» виден в левом баре, открывается, список диалогов и композер работают
   без сервера Марса (проверка на POCO).
4. Живой запрос к PlusVibe с реальным ключом: ответ стримится, `tool_call` на будильник
   приходит и после «Разрешить» будильник встаёт в системных часах.
5. Ключ не появляется ни в `SharedPreferences`, ни в логах, ни в `git status`.
