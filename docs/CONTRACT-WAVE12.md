# Контракт волны 12: закрытие хвостов волны 1 (метрики, обрезка) + устойчивость провайдеров

Дата: 01.11.2026. База: ветка `feat/local-chat-wave`, коммит `6aca02e8`.
Координатор уже внёс правки в ядро (§1) — воркеры их ИСПОЛЬЗУЮТ и НЕ МЕНЯЮТ.

Волна состоит из двух независимых потоков:

- **Поток A** — доделать то, что волна 1 честно оставила незакрытым: `truncated` по
  `finish_reason`, метрики хода в истории, кнопка «Продолжить» при обрезке.
- **Поток B** — устойчивость к смене провайдера/URL: гонка ответа со сменой адреса,
  кэш возможностей модели, ключ только в Keystore, мьютекс смены модели.

Потоки не пересекаются по файлам, поэтому идут параллельно.

---

## §1. Что координатор УЖЕ изменил в ядре (использовать как есть)

Модуль `core-network`, каталог `core-network/src/main/java/ru/mars/network/local/`.

### `LocalChatModels.kt` — `AssistantFinished` получил третий параметр

```kotlin
data class AssistantFinished(
    val fullText: String,
    val toolCalls: List<LocalToolCall>,
    /** Дословный `finish_reason` провайдера: `stop`/`length`/`tool_calls`/... либо `null`. */
    val finishReason: String? = null,
) : LocalChatEvent
```

`OkHttpLocalChatClient.mapSse` уже пробрасывает `finish_reason` из парсера
(`LocalSseParser.Event.Finished.finishReason` — он там БЫЛ всегда, терялся только на маппинге).
Провайдер, не приславший причину, даёт `null` — это норма, а не ошибка.

### `LocalChatModels.kt` — `LocalMessage` получил метрики, появился новый тип

```kotlin
data class LocalMessage(
    val id: String,
    val role: LocalRole,
    val text: String = "",
    val toolCalls: List<LocalToolCall> = emptyList(),
    val toolCallId: String? = null,
    val createdAt: Long = 0L,
    val attachments: List<LocalAttachment> = emptyList(),
    /** Метрики хода для футера; `null` — шаг не измерялся. */
    val metrics: LocalTurnMetrics? = null,
)

data class LocalTurnMetrics(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val ttftMs: Long = 0L,
    val interrupted: Boolean = false,
    val truncated: Boolean = false,
    val finishReason: String? = null,
) {
    val isMeasured: Boolean
        get() = promptTokens > 0 || completionTokens > 0 || totalTokens > 0 || ttftMs > 0L

    companion object {
        const val REASON_LENGTH = "length"
    }
}
```

**Правила:**
- `metrics` живёт ТОЛЬКО у `LocalRole.ASSISTANT`. У `USER` и `TOOL` — всегда `null`.
- В тело запроса к провайдеру `metrics` не уходит НИКОГДА (это метрика клиента).
- `isMeasured == false` → метрику не сохраняем (`null`), футер не рисуем.

---

## §2. Поток A — закрыть хвосты волны 1

### A1. Экран: `truncated` из `finish_reason` (только `app-mobile`)

Файл: `app-mobile/src/main/java/ru/mars/mobile/LocalChatPresenter.kt`.

Сейчас в `LocalChatItem` есть поля `truncated`, `interrupted`, `ttftMs`, но `truncated`
никогда не выставляется — в KDoc прямо написано «транспорт не пробрасывает
`finish_reason`». Теперь пробрасывает.

**Что сделать:**
1. В обработчике `is LocalChatEvent.AssistantFinished` выставить
   `truncated = event.finishReason == LocalTurnMetrics.REASON_LENGTH`.
   Место — там же, где сейчас `updateItem(assistantId) { it.copy(text = full, streaming = false) }`.
2. В `LocalTurnFooter.line(...)` добавить параметр `truncated: Boolean = false` и часть
   строки `"обрезан"` (константа `TRUNCATED`) — по образцу существующей `INTERRUPTED`.
   Порядок частей: токены → запрос/окно → ttft → `оборван` → `обрезан`.
3. Не выставлять `truncated` на сообщениях человека и на TOOL.

**Критерий приёмки:** JVM-тест без эмулятора на `LocalTurnFooter.line`:
`truncated = true` даёт строку с `обрезан`; `truncated = false` её не даёт;
`finishReason = "stop"` не выставляет флаг, `"length"` — выставляет.

### A2. Метрики хода переживают перезапуск (только `app-mobile`)

Файл: `app-mobile/src/main/java/ru/mars/mobile/LocalSessionStore.kt`
(object `LocalSessionJson` внутри него).

**Что сделать:**
1. `encodeMessage`: писать `metrics` вложенным объектом ТОЛЬКО когда `message.metrics != null`
   и `isMeasured` (свежий диалог остаётся байт в байт прежним — это требование волны 1).
   Ключи: `promptTokens`, `completionTokens`, `totalTokens`, `ttftMs`, `interrupted`,
   `truncated`, `finishReason` (последний — только если не null).
2. `decodeMessage`: читать `metrics` обратно; битое/отсутствующее поле → `null`, а не
   исключение. Старые файлы без `metrics` обязаны читаться как раньше.
3. Токены — существующим `optTokens`-подходом (терпимым к строке с числом), булевы —
   терпимо к отсутствию.

**Совместимость:** файл версии 1 открывается, диалог не теряется. Это проверяется тестом.

### A3. Связка метрик с историей в презентере (только `app-mobile`)

Файл: тот же `LocalChatPresenter.kt`.

Сейчас в `finishTurn(...)` и в ветке `AssistantFinished` создаётся `LocalMessage(...)`
без `metrics`. Надо:
- сформировать `LocalTurnMetrics` из уже существующих `turnUsage`/`ttft`/`interrupted`/
  `truncated`/`finishReason` и положить в `LocalMessage` ответа;
- в `restore(messages, usage)` — поднимать `ttftMs`/`interrupted`/`truncated` из
  `message.metrics` в элемент ленты, чтобы футер после перезапуска был тем же.

**Запрет:** не менять `LocalTurnFooter` логику расчёта токенов и не трогать `recordUsage`.

### A4. «Продолжить» при обрезке — ТОЛЬКО политика, без разметки

Новый файл: `app-mobile/src/main/java/ru/mars/mobile/LocalContinuePolicy.kt`.

Чистая функция без `android.*`: решает, показывать ли кнопку «Продолжить» и какое
сообщение дописать в историю.

```kotlin
object LocalContinuePolicy {
    /** Показывать ли предложение продолжить: только честная обрезка, не обрыв и не пустой ответ. */
    fun shouldOffer(truncated: Boolean, interrupted: Boolean, text: String): Boolean

    /** Текст служебной реплики, которую дописываем в историю перед продолжением. */
    const val CONTINUE_PROMPT: String

    /**
     * Обрезка по лимиту выходных токенов — единственный случай, когда продолжение осмысленно:
     * `tool_calls` завершились сами, `stop` — тоже, а `content_filter` продолжать бессмысленно.
     */
    fun reasonAllowsContinue(finishReason: String?): Boolean
}
```

**ВНИМАНИЕ:** кнопку в разметку НЕ вставлять и экран НЕ трогать — это зона координатора.
Файл должен только компилироваться и быть покрыт тестом.

---

## §3. Поток B — устойчивость провайдеров

### B1. Гонка ответа со сменой URL (только `core-network`)

Файлы: `OkHttpLocalChatClient.kt`, `LocalProvider.kt` (или новый `LocalProviderSeq.kt`).

**Дефект:** человек меняет адрес сервера (или модель) в настройках, пока идёт поток.
Ответ по СТАРОМУ адресу приходит позже и сейчас может быть записан как ответ по новому.

**Что сделать:**
1. Счётчик-метка конфигурации: `readSeq` — монотонно растущий `Long`, инкремент при
   смене профиля/URL/модели.
2. `isOvertaken(seq)` — текущая метка отличается от снятой в начале запроса.
3. В `OkHttpLocalChatClient.stream`: снять метку в начале, в каждом эмите проверять
   `isOvertaken` — при расхождении ОСТАНОВИТЬ поток, не эмитить дальше и НЕ считать
   это ошибкой (`LocalChatEvent.Error` не отправлять: человек сам сменил адрес).

**Критерий приёмки:** JVM-тест на фейковом клиенте/метке: смена метки посреди потока
→ после неё событий нет; без смены — поток полный.

### B2. Кэш возможностей модели с инвалидацией (только `core-network`)

Новый файл: `core-network/src/main/java/ru/mars/network/local/LocalCapabilityCache.kt`.

Правило владельца проекта: **«неизвестно ≠ нет», fail-closed при смене URL.**

```kotlin
class LocalCapabilityCache {
    /** Возможности модели для текущего URL; `null` — неизвестно (кэш пуст или сброшен). */
    fun get(providerKey: String, modelId: String): LocalModelInfo?

    /** Запомнить разобранный каталог для конкретного URL провайдера. */
    fun put(providerKey: String, models: List<LocalModelInfo>)

    /** Сброс всего кэша: вызывается при любой смене URL/ключа/типа API. */
    fun invalidate()
}
```

**Требования:**
- Ключ кэша обязан включать адрес провайдера: после смены URL старые возможности
  НЕ отдаются (иначе «модель умеет картинки» окажется правдой для чужого сервера).
- Пустой результат `GET /models` — не «нет возможностей», а «неизвестно»: не кэшировать
  как факт отсутствия.
- Потокобезопасность: `synchronized` или `ConcurrentHashMap` — вызовы идут с разных нитей.

**Критерий приёмки:** JVM-тесты: `put` → `get` отдаёт; `invalidate()` → `null`;
другой `providerKey` → `null`.

### B3. Ключ провайдера только в Keystore (только `app-mobile`)

Файл: `app-mobile/src/main/java/ru/mars/mobile/LocalProviderPrefsStore.kt`.

**Что сделать:** убрать хранение ключа в `SharedPreferences`, если оно там есть;
использовать существующий `ru.mars.network.credential.AndroidKeyStoreCredentialStore`
(тот же, что у серверного чата). В `SharedPreferences` остаются только URL, тип API,
имя модели — не секреты.

**Обязательно:** миграция. Если в старых prefs лежит ключ — перенести в Keystore и
СТЕРЕТЬ из prefs. Пользователь не должен вводить ключ заново.

**Запрет:** не печатать ключ в логи, не отдавать его в отчёт. В отчёте — только имя
метода и факт «перенесён».

### B4. Мьютекс смены модели, last-one-wins (только `app-mobile`)

Файл: `app-mobile/src/main/java/ru/mars/mobile/LocalModelPickerPolicy.kt` (или новый).

**Дефект-гипотеза:** два быстрых выбора модели подряд могут применить первый результат
после второго (пришёл позже) — на экране оказывается не та модель, что нажали последней.

**Что сделать:** чистая политика без `android.*`:
```kotlin
object LocalModelPickPolicy {
    /** Чей результат применить: применяется только последний запрошенный. */
    fun shouldApply(requestSeq: Long, latestSeq: Long): Boolean

    /** Следующая метка запроса из текущей. */
    fun nextSeq(current: Long): Long
}
```

**Критерий приёмки:** JVM-тест: `(1,2) → false`, `(2,2) → true`, монотонность `nextSeq`.

**ВНИМАНИЕ:** подключение политики к экрану — зона координатора. Файл только
компилируется и покрывается тестом.

---

## §4. Разделение файлов (нарушение = откат работы)

| Файл / каталог | Поток A | Поток B | Координатор |
|---|---|---|---|
| `core-network/.../local/LocalChatModels.kt` | **НЕ ТРОГАТЬ** | **НЕ ТРОГАТЬ** | правит |
| `core-network/.../local/OkHttpLocalChatClient.kt` | **НЕ ТРОГАТЬ** | правит | правит |
| `core-network/.../local/LocalCapabilityCache.kt` | — | создаёт | — |
| `core-network/.../local/LocalProvider*.kt` | — | правит | — |
| `app-mobile/.../LocalChatPresenter.kt` | правит | **НЕ ТРОГАТЬ** | правит |
| `app-mobile/.../LocalSessionStore.kt` | правит | **НЕ ТРОГАТЬ** | — |
| `app-mobile/.../LocalContinuePolicy.kt` | создаёт | — | — |
| `app-mobile/.../LocalProviderPrefsStore.kt` | **НЕ ТРОГАТЬ** | правит | — |
| `app-mobile/.../LocalModelPickPolicy.kt` | — | создаёт | — |
| `app-mobile/.../LocalChatActivity.kt` | **НЕ ТРОГАТЬ** | **НЕ ТРОГАТЬ** | правит |
| `app-mobile/.../LocalModelPicker*.kt` (экран) | — | **НЕ ТРОГАТЬ** | правит |
| `*.gradle.kts`, `libs.versions.toml`, `AndroidManifest.xml` | **НЕ ТРОГАТЬ** | **НЕ ТРОГАТЬ** | правит |
| тесты в своём модуле | создаёт | создаёт | — |

Тесты: поток A — в `app-mobile/src/test/java/ru/mars/mobile/`, поток B — в
`core-network/src/test/java/ru/mars/network/local/` (плюс `app-mobile` для B3/B4).

---

## §5. Запреты для обоих потоков

1. **Gradle не запускать.** Сборку делает координатор. Не собирать kotlinc, не строить
   свои classpath, не делать `stubs.kt`. «0 errors» из самодельного харнесса не принимается.
2. **Не создавать типы, которые уже есть в ядре.** Импортировать, а не дублировать.
   Новый общий тип в `core-network` — только через координатора.
3. **Не менять `LocalChatEvent`, `LocalTurnMetrics`, `LocalMessage`** — это правки координатора.
4. **Не трогать чужой поток** по таблице §4. Запрет «не трогает» важнее «правит».
5. **Не править тесты, которые уже есть.** Свои новые — можно.
6. **Не печатать в отчёт ключи, токены, содержимое `.env`.** Маскированные значения
   (`***`) в выводе инструментов — это редакция системы, файл корректен.
7. **Не выдумывать успешный прогон.** Не собирал — так и написать. Проверял чтением —
   так и написать.
8. Не менять формат SSE-парсера: `finish_reason` уже разбирается корректно.

---

## §6. Что требовать в отчёте

- Созданные/изменённые файлы с числом строк.
- Что проверено и ЧЕМ (чтение, вынос чистой логики, javap).
- Что НЕ проверено и почему.
- Какие типы добавлены сверх контракта (с сигнатурами).
- Где контракт противоречил реальности кода (это ценно, не скрывать).
