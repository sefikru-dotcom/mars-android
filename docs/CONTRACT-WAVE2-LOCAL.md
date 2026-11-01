# Контракт волны 2: локальный чат до паритета с серверным

Волна 1 дала работающий, но скудный чат: можно отправить сообщение, увидеть поток и
карточку инструмента. Серверный чат (`ChatActivity`, 30+ файлов) умеет в разы больше.
Задача волны — закрыть разрыв и добавить остаток плана (голос, офлайн-синтез, фон).

**Координатор УЖЕ расширил ядро** (см. §1). Воркер эти типы только использует и НЕ
меняет. Если сигнатуры не хватает — пиши в отчёте, не заводи дубль у себя.

## §1. Ядро: что уже есть и что добавлено координатором

Модуль `core-network`, каталог `core-network/src/main/java/ru/mars/network/local/`.
**Импортов `android.*` здесь быть не может** — модуль JVM.

### Уже существовало (не трогать)
- `LocalRole { SYSTEM, USER, ASSISTANT, TOOL }`
- `LocalMessage`, `LocalToolCall`, `LocalToolSchema`, `LocalSession`
- `LocalProviderProfile`, `LocalProviderStore`, `LocalProviderUrls`
- `LocalChatClient` — `stream(...)` и `models(...)`
- `LocalSseParser`, `LocalRetryPolicy`, `LocalAgentLoop`, `OkHttpLocalChatClient`

### Добавлено в этой волне (читать в файлах, использовать как есть)

`LocalChatModels.kt`:
```kotlin
/** Токены одного шага, из финального кадра usage (stream_options.include_usage). */
data class LocalUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
)

sealed interface LocalChatEvent {
    data class Delta(val text: String) : LocalChatEvent
    data class ReasoningDelta(val text: String) : LocalChatEvent
    data class ToolRequested(val call: LocalToolCall) : LocalChatEvent
    data class ToolFinished(val callId: String, val ok: Boolean, val preview: String) : LocalChatEvent
    data class AssistantFinished(val fullText: String, val toolCalls: List<LocalToolCall>) : LocalChatEvent
    /** НОВОЕ: расход шага. Приходит ПЕРЕД AssistantFinished, если провайдер его отдал. */
    data class UsageReported(val usage: LocalUsage) : LocalChatEvent
    data class Error(val message: String, val retryable: Boolean = false) : LocalChatEvent
}
```

`LocalModelCatalog.kt` (новый, координатор):
```kotlin
/** Метаданные модели из GET /models: окно контекста и управление reasoning. */
data class LocalModelInfo(
    val id: String,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    /** true, если провайдер сообщил `reasoning_controls: ["reasoning_effort"]`. */
    val supportsReasoningEffort: Boolean = false,
    val inputModalities: List<String> = emptyList(),
)

object LocalModelCatalog {
    /** Разбор ответа /models. Неизвестные поля игнорируются, битый JSON — пустой список. */
    fun parse(json: String): List<LocalModelInfo>
}
```

`LocalChatClient.kt` — добавляется метод (реализация в `OkHttpLocalChatClient`):
```kotlin
/** `GET {base}/models` с метаданными (окно контекста, reasoning). */
suspend fun catalog(profile: LocalProviderProfile, apiKey: String): Result<List<LocalModelInfo>>
```
**`models()` остаётся**: экран настроек зовёт его, ломать сигнатуру нельзя.

`LocalProviderProfile` получает поле:
```kotlin
/** Имя модели для `reasoning_effort`; null = «Авто» (поле не отправляется). */
val reasoningEffort: String? = null   // УЖЕ БЫЛО
/** Последний известный размер окна контекста этой модели (из /models); null — неизвестен. */
val contextWindow: Int? = null        // ДОБАВЛЕНО
```

## §2. Живые факты о провайдере (проверены curl'ом, не выдумывать иное)

Адрес `https://plusvibeapi.ru/v1`, 85 моделей.

1. `stream_options: {"include_usage": true}` **работает**: приходит финальный кадр
   `{"choices":[],"usage":{...}}`. Поля: `prompt_tokens`, `completion_tokens`,
   `total_tokens`, плюс `cache_read_input_tokens`. Кадр приходит **после**
   `finish_reason` и **перед** `[DONE]`.
2. `GET /models` отдаёт по каждой модели: `id`, `context_length`, `context_window`,
   `max_input_tokens`, `max_output_tokens`, `architecture.input_modalities`,
   `reasoning_controls` (массив строк).
3. `reasoning_controls` **непустой только у 14 из 85 моделей**; у `claude-haiku-5.5`
   его НЕТ. Значит переключатель reasoning показывается **только** если
   `supportsReasoningEffort == true`. Всегда показывать нельзя — это мёртвая кнопка.
4. `reasoning_effort: "high"` принимается без ошибки (для моделей без поддержки
   просто игнорируется). Уровни: `low`, `medium`, `high` (+ «Авто» = null).

## §3. Нарезка: кто что пишет

Один воркер — один каталог/группа файлов. Строка «НЕ трогает» важнее «трогает».

### W1 — ядро: usage и каталог моделей
**Трогает:** `core-network/src/main/java/ru/mars/network/local/` — только
`LocalSseParser.kt`, `OkHttpLocalChatClient.kt`.
**НЕ трогает:** `LocalChatModels.kt`, `LocalChatClient.kt`, `LocalModelCatalog.kt`,
`LocalProvider.kt`, `LocalAgentLoop.kt`, `LocalRetryPolicy.kt`, любые тесты, `app-mobile`.

Задачи:
1. `LocalSseParser`: разбирать финальный кадр с пустым `choices` и `usage` →
   событие `Event.Usage(...)` (внутренний вариант парсера). Кадр приходит после
   `finish_reason`, поэтому парсер обязан отдать `Usage` ДО `AssistantFinished`.
2. `LocalSseParser`: не ломаться на `usage` без `choices`, на `choices: []`, на
   отсутствующем `usage`, на отрицательных/дробных значениях (приводить к Int
   безопасно, мусор → 0).
3. `OkHttpLocalChatClient`: в тело запроса добавить
   `"stream_options":{"include_usage":true}` — **всегда**, это не ломает ни одного
   провайдера (лишнее поле игнорируется).
4. `OkHttpLocalChatClient`: маппить `Event.Usage` → `LocalChatEvent.UsageReported`.
5. `OkHttpLocalChatClient`: реализовать `catalog(...)` — `GET {base}/models`, разбор
   через `LocalModelCatalog.parse`, ошибки → `Result.failure`. Таймауты как у `models()`.

### W2 — презентер: модель, reasoning, расход, действия
**Трогает:** `app-mobile/src/main/java/ru/mars/mobile/LocalChatPresenter.kt` и новые
файлы `LocalChat*.kt` с префиксом `LocalChat` в этом же каталоге.
**НЕ трогает:** `LocalSessionStore.kt`, `LocalProviderPrefsStore.kt`, любые
`Activity*.kt`, `LocalChatTranscript.kt`, `LocalChatToolSource.kt`,
`LocalChatConfirmDialog.kt`, `localtools/`, `core-network/`, тесты.

Задачи:
1. `LocalChatUiState` расширяется (это единственный источник правды для рендера):
```kotlin
data class LocalChatUiState(
    val items: List<LocalChatItem> = emptyList(),
    val streaming: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
    /** Текущий расход диалога: сумма usage по всем шагам. */
    val usage: LocalUsage = LocalUsage(),
    /** Заполнение окна: promptTokens последнего шага / contextWindow модели. */
    val context: LocalContextView? = null,
    /** Модель активного профиля и её окно (для пилюли и выбора). */
    val model: String? = null,
    val reasoningEffort: String? = null,
    val supportsReasoning: Boolean = false,
)
```
2. `LocalContextView` — **чистая** политика в отдельном файле `LocalContextPolicy.kt`
   без `android.*`, по образцу серверных `ContextBudgetPolicy`/`SessionUsagePolicy`:
   процент заполнения, уровень (`NORMAL`/`WARNING`/`CRITICAL` пороги 50/75),
   текст пилюли (`"12.4k / 200k · 6%"`), `contentDescription` для скринридера,
   детальный диалог. **Неизвестное окно не превращается в фейковый процент** —
   `visible = false`.
3. Презентер накапливает `UsageReported` в `usage` (сумма) и пересчитывает `context`
   по `promptTokens` последнего шага. Сохранение в файл — только через переданный
   `onPersist`, формат не меняется (usage переживает перезапуск: хранить в мете сессии
   как `totalPromptTokens`/`totalTokens` — поля меты добавляет W3).
4. Методы смены модели/уровня на стороне презентера: `setModel(id)`, `setReasoning(effort)`
   — меняют активный профиль через переданный колбэк и сбрасывают контекст.
5. Действия над сообщением (чистые функции, без `android.*`): `canCopy`, `canRetry`,
   `canEdit`, `canSpeak`, тексты contentDescription по-русски. Само выполнение
   (буфер, повтор, правка) — на стороне экрана.

### W3 — хранилище сессий: метаданные и действия
**Трогает:** `app-mobile/src/main/java/ru/mars/mobile/LocalSessionStore.kt` **и только его**
(плюс новый `LocalSessionsPolicy.kt`, если нужна чистая логика фильтра/поиска).
**НЕ трогает:** `LocalChatPresenter.kt`, любые `Activity*.kt`, `core-network/`, тесты.

Задачи:
1. В мету сессии добавить (обратная совместимость обязательна: старые файлы читаются
   без ошибки, отсутствующие поля = значения по умолчанию):
   - `model: String?` — модель, на которой шёл диалог;
   - `totalPromptTokens: Long`, `totalTokens: Long` — накопленный расход;
   - `reasoningEffort: String?`.
2. `StoredSession` получает те же поля.
3. Добавить: `search(query: String): List<LocalSession>` (по заголовку и тексту,
   регистронезависимо), `rename(id, title)`, `duplicate` **не нужно**.
4. Лимиты и атомарность сохранить как есть (200 сообщений, 100 диалогов, `.tmp`+rename).

### W4 — экран чата: пилюля контекста, модель, reasoning, действия
**Трогает:** `LocalChatActivity.kt`, `LocalChatTranscript.kt`, новые
`LocalChatComposerBar.kt`, `LocalChatActionsSheet.kt`, `LocalChatModelPicker.kt`.
**НЕ трогает:** `LocalChatPresenter.kt`, `LocalSessionStore.kt`,
`LocalProviderSettingsActivity.kt`, `LocalSessionsActivity.kt`, `core-network/`,
`localtools/`, `AndroidManifest.xml`, `res/`, тесты.

Задачи:
1. **Пилюля контекста** в композере (по образцу серверной): кольцо/текст из
   `state.context`, цвет по уровню, тап → диалог с деталями (`prompt`/`completion`,
   процент, порог предупреждения). Скрыта, если окно неизвестно.
2. **Кнопка модели** в шапке: текущая модель ↓, тап → `LocalChatModelPicker` —
   список из `catalog()` с поиском, окном контекста и меткой поддержки reasoning.
   Выбор меняет активный профиль (через колбэк презентера, не напрямую).
3. **Кнопка reasoning**: видна **только** при `state.supportsReasoning`, пункты
   Авто / low / medium / high, текущий помечен.
4. **Работа с текстом**: долгое нажатие на сообщение → лист действий
   (Копировать, Повторить [только последний ответ ассистента], Выбрать текст,
   Озвучить, Поделиться). Копирование — `ClipboardManager`, озвучка — через
   существующий `AndroidSpeechEngine`/серверный TTS-порт, если доступен; если нет —
   пункт скрыт.
5. **Markdown в ответах**: использовать существующий `ChatMarkdownRenderer`, не писать свой.
6. Всё в классических View, цвета только из `MarsTheme.*`, шапка через
   `SectionScaffold.create`. Никаких новых зависимостей.

### W5 — экран списка диалогов и настройки
**Трогает:** `LocalSessionsActivity.kt`, `LocalProviderSettingsActivity.kt`.
**НЕ трогает:** `LocalChatActivity.kt`, `LocalSessionStore.kt`, `LocalChatPresenter.kt`,
`core-network/`, `localtools/`, `AndroidManifest.xml`, тесты.

Задачи:
1. Список диалогов: поиск по истории, показ модели и расхода в строке, свайп/долгое
   нажатие → переименовать/удалить, пустое состояние с подсказкой.
2. Настройки провайдера: **заменить текстовое поле модели на выбор из `catalog()`**
   (поиск, окно контекста, метка reasoning), reasoning-селектор (только при поддержке),
   temperature/maxTokens, несколько профилей с переключением.
3. Кнопка «Проверить» показывает не только число моделей, но и окно выбранной модели.

### W6 — голос в локальном чате (остаток плана, часть 1)
**Трогает:** новые файлы `LocalVoice*.kt` в `app-mobile/src/main/java/ru/mars/mobile/`.
**НЕ трогает:** существующие экраны, `core-network/`, `localtools/`, тесты.

Задача: мост «микрофон → ASR → композер» и «ответ → TTS» для локального чата,
используя **уже существующие** `AndroidSpeechEngine`, `AsrEnginePort`, `AsrEvent`,
`AndroidServerTtsPlayback`, `BargeInGate`, `AudioRoutePolicy`. Порт-интерфейс с
необязательными зависимостями (`onOpenX: (() -> Unit)? = null`), чтобы координатор
подключил одной правкой. Никакого второго движка распознавания.

## §4. Запреты для всех воркеров

- **Не запускать Gradle** (ни `./gradlew`, ни `build-mars.sh`) — сборка только у координатора.
- **Не трогать git** (никаких `add`/`commit`/`checkout`).
- **Не добавлять зависимости** в `*.gradle.kts` / `libs.versions.toml`.
- **Не править** `AndroidManifest.xml`, `res/`, `strings.xml`, тесты, чужие каталоги.
- **Не создавать общие типы** в своём файле — если нужен новый общий тип, пиши в отчёте.
- **Не выдумывать API провайдера**: факты в §2 проверены живым запросом.
- **Маскировка:** если в выводе инструмента видишь `apiKey: ***` — это редакция
  секрета системой, файл корректен, тип писать `String`.
- **Не заявлять успешный прогон**, которого не было. «Не проверял» — правильный ответ.

## §5. Критерии приёмки (проверяет координатор на POCO F5)

1. Пилюля контекста показывает реальные токены после первого хода и не врёт при
   неизвестном окне.
2. Переключение модели в чате меняет модель следующего запроса (проверяется по
   ответу провайдера).
3. Reasoning виден только у моделей с `reasoning_controls` и реально уходит в запрос.
4. Копирование/повтор/правка/озвучка работают на реальном сообщении.
5. Markdown рендерится (код, списки, ссылки).
6. Поиск по диалогам и переименование работают после перезапуска приложения.
7. Голосовой ввод заполняет композер, ответ озвучивается.
8. Release-сборка не теряет классы (проверка dex), подпись боевым ключом.
