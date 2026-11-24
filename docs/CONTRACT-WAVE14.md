# Контракт волны 14 — «возможности из ChatGPT/Gemini, которых у нас нет»

Состояние базы: ветка `feat/local-chat-wave`, коммит `993634a6`, дерево чистое.
`:app-mobile:testDebugUnitTest` — BUILD SUCCESSFUL, 3266 тестов, 0 падений.
`:app-mobile:assembleRelease` — BUILD SUCCESSFUL, R8 сохранил все классы волны 13.

Проверено координатором по диску перед нарезкой (не по документам пробелов):

| Что искали | Факт |
|---|---|
| режим перевода | НЕТ ни политики, ни экрана; `translate` встречается только в музыке и аудио |
| артефакт-панель | НЕТ; `DocumentBuilder.buildDocx/buildXlsx/buildTxt` есть, результат уходит только в «Поделиться» |
| ответ из уведомления | НЕТ; `fcm/PushNotifications.kt` (134 стр.) рисует `BigTextStyle` без `addAction`/`RemoteInput` |
| индикатор «запомнил» в ленте | НЕТ; `RememberTool` пишет в `MemoryStore`, но в ленте об этом ни строки |

Волна — ЧЕТЫРЕ независимых потока. Воркер делает ТОЛЬКО СВОЙ поток.

| Поток | Что | Зона (единственная) |
|---|---|---|
| A | Режим перевода: политика + экран-витрина | `Translate*` |
| B | Артефакт-панель поверх DocumentBuilder | `Artifact*` |
| C | Индикатор «запомнил» в ленте локального чата | `MemoryNotice*` |
| D | Ответ прямо из push-уведомления | `PushReply*` |

---

## Железные правила (нарушение = отказ работы)

1. **Не запускать Gradle, gradlew, kotlinc, javac.** Сборку делает только координатор.
   Проверять чтением и выносом чистой логики в тестируемый вид.
2. **Не трогать общие файлы** (список ниже). Это зона координатора.
3. **Новых зависимостей не добавлять** (`*.gradle.kts`, каталог версий — чужая зона).
4. **Публичные методы существующих классов не менять** — экран уже их вызывает.
5. Все новые политики — **чистый JVM**, без `android.*`: проверяются юнит-тестами.
   Android-зависимые View/Activity — отдельными файлами.
6. Пользовательские тексты — по-русски, в стиле существующих (`LocalChatActions.label`,
   `MoonPhase`-подобных нет: смотри `LocalContinuePolicy`).
7. Маскированные значения (`***`) в выводе — редакция секрета, файл цел.
8. **Никаких новых микрофонов, TTS-движков и аудиофокусов.** Голос в проекте уже
   принадлежит `LocalVoiceController`; второй захват — это «эхо» и «микрофон занят».

## Чужая зона — НЕ ТРОГАТЬ ни одному воркеру

```
LocalChatActivity.kt        LocalChatPresenter.kt      LocalChatTranscript.kt
LocalChatComposerBar.kt     ChatActivity.kt             ChatInitSection.kt
ChatMessageActionsSection.kt ChatTurnSection.kt         SettingsActivity.kt
MainActivity.kt             MarsTheme.kt                MarsDialog.kt
MarsGradients.kt            DocumentBuilder.kt          DocumentTextExtractor.kt
MemoryStore.kt              LocalMemoryActivity.kt      RememberTool.kt
LocalVoiceController.kt     LocalVoiceTts.kt            LocalVoiceButton.kt
fcm/PushNotifications.kt    fcm/MarsFirebaseMessagingService.kt
VoiceOutputController.kt    ServerTtsSpeechEngine.kt    AndroidSpeechEngine.kt
*.gradle.kts, libs.versions.toml, gradle/*, AndroidManifest.xml
```

Манифест, навигацию и вставки в экраны делает координатор.

---

## Поток A — режим перевода

**Зачем.** У Gemini это отдельный режим: говоришь по-русски — слышишь по-английски.
У нас такого нет вовсе. Каскад STT → LLM → TTS уже работает, поэтому перевод — это
не новая технология, а промпт и экран.

**Границы потока (важно).** Экран перевода НЕ открывает микрофон и НЕ создаёт синтез.
Он получает распознанный текст и отдаёт готовый перевод наружу через колбэки. Подключение
к голосовому каналу делает координатор.

**Что создать:**

1. `app-mobile/src/main/java/ru/mars/mobile/TranslatePolicy.kt` — чистый JVM:

   ```kotlin
   /** Язык перевода. Тег BCP-47 нужен промпту и синтезу. */
   data class TranslateLanguage(
       val code: String,
       val label: String,
       val nativeLabel: String,
   )

   /** Направление: с какого языка на какой. */
   data class TranslateDirection(
       val from: TranslateLanguage,
       val to: TranslateLanguage,
   ) {
       val isSame: Boolean get() = from.code == to.code
   }

   object TranslatePolicy {
       const val TITLE: String = "Перевод"

       /** Список языков для выбора. Первые два — русский и английский. */
       val LANGUAGES: List<TranslateLanguage> = listOf(
           TranslateLanguage("ru", "Русский", "Русский"),
           TranslateLanguage("en", "Английский", "English"),
           TranslateLanguage("de", "Немецкий", "Deutsch"),
           TranslateLanguage("tr", "Турецкий", "Türkçe"),
       )

       fun defaultDirection(): TranslateDirection

       /** Переставить направление местами — главный жест в этом режиме. */
       fun swap(direction: TranslateDirection): TranslateDirection

       /**
        * Системный промпт перевода.
        *
        * Требования, без которых результат бесполезен в голосовом режиме: только перевод,
        * без пояснений и кавычек, без транслитерации, без markdown — ответ читается вслух.
        */
       fun systemPrompt(direction: TranslateDirection): String

       /** Проверка, что ответ модели похож на перевод, а не на отказ или лекцию. */
       fun looksLikeTranslation(answer: String): Boolean

       /** Заготовка реплики человека: что показывать, пока перевода нет. */
       fun pendingHint(direction: TranslateDirection): String
   }
   ```

2. `app-mobile/src/main/java/ru/mars/mobile/TranslateSheet.kt` — View-слой, стиль
   `MarsDialog`. Публичный API ровно такой:

   ```kotlin
   internal object TranslateSheet {
       fun show(
           activity: Activity,
           current: TranslateDirection,
           onApply: (TranslateDirection) -> Unit,
       )
   }
   ```

   Внутри — два списка языков пилюлями (исходный / целевой) и кнопка «Поменять».
   Недоступный выбор (тот же язык с двух сторон) — неактивен, без мёртвых нажатий.

**Приёмка потока A** (тесты пишет отдельный проход, но перечисли случаи в отчёте):
- `swap` дважды возвращает исходное направление;
- `systemPrompt` содержит оба языка и не содержит markdown-разметки;
- `systemPrompt` при `isSame == true` не собирается — вместо него `null` или явный
  отказ: переводить с русского на русский бессмысленно;
- `looksLikeTranslation("")` → `false`; `looksLikeTranslation("Извините, я не могу")` → `false`;
- `LANGUAGES` без дублей кодов, первый — `ru`.

---

## Поток B — артефакт-панель

**Зачем.** У ChatGPT Canvas и Claude Artifacts длинный ответ открывается в отдельной
панели с кнопками «править / скачать / вернуть в чат». У нас `DocumentBuilder` уже
собирает DOCX/XLSX/TXT из текста, но результат живёт как вложение: чтобы посмотреть
длинный документ, человек листает ленту.

**Что создать:**

1. `app-mobile/src/main/java/ru/mars/mobile/ArtifactPolicy.kt` — чистый JVM:

   ```kotlin
   /** Что считать артефактом: длинный структурированный ответ, а не любое сообщение. */
   enum class ArtifactKind { TABLE, CODE, DOCUMENT, NONE }

   data class ArtifactDecision(
       val kind: ArtifactKind,
       val suggestedTitle: String,
       /** Готовый формат для сохранения: DOCX, XLSX, TXT. */
       val suggestedFormat: ArtifactFormat,
   ) { val isArtifact: Boolean get() = kind != ArtifactKind.NONE }

   enum class ArtifactFormat { DOCX, XLSX, TXT }

   object ArtifactPolicy {
       /** Порог «это уже не сообщение, а документ»: 12 строк или 900 символов. */
       const val MIN_LINES: Int = 12
       const val MIN_CHARS: Int = 900

       fun decide(text: String): ArtifactDecision
       /** Заголовок панели: первая содержательная строка без markdown-мусора. */
       fun title(text: String): String
       /** Подпись кнопки сохранения по формату. */
       fun saveLabel(format: ArtifactFormat): String
       /** Сколько таблиц/блоков кода в тексте — для подписи в панели. */
       fun countBlocks(text: String, kind: ArtifactKind): Int
   }
   ```

   Правила `decide` (обязаны быть):
   - текст с markdown-таблицей (`| … |`) и ≥ 2 строк таблицы → `TABLE` + `XLSX`;
   - текст с блоком ```` ``` ```` и ≥ `MIN_LINES` строк → `CODE` + `TXT`;
   - иначе если строк ≥ `MIN_LINES` **или** символов ≥ `MIN_CHARS` → `DOCUMENT` + `DOCX`;
   - всё остальное → `NONE`.

2. `app-mobile/src/main/java/ru/mars/mobile/ArtifactSheet.kt` — View-слой:
   ```kotlin
   internal object ArtifactSheet {
       fun show(
           activity: Activity,
           decision: ArtifactDecision,
           text: String,
           onSave: (ArtifactFormat) -> Unit,
           onCopy: () -> Unit,
           onShare: () -> Unit,
       )
   }
   ```
   Полноэкранный лист `MarsDialog` с прокручиваемым текстом (моноширинный шрифт для
   `CODE`), подпись «N строк», три кнопки. `MarsDialog.textField` не использовать для
   отображения — он редактируемый; нужен `TextView` в `ScrollView`.

**Приёмка потока B:** таблица из 3 строк → `TABLE`/`XLSX`; 5 строк обычного текста →
`NONE`; 15 строк → `DOCUMENT`/`DOCX`; блок кода из 20 строк → `CODE`/`TXT`;
`title` не содержит `#`, `*`, `` ` ``; пустой текст → `NONE`.

---

## Поток C — индикатор «запомнил» в ленте

**Зачем.** `RememberTool` пишет факт в память молча. Человек не видит, что именно
отложилось, и не может возразить — а ChatGPT показывает «вот что я о тебе помню».
Экран памяти у нас есть (`LocalMemoryActivity`), но он в другом месте и не в моменте.

**Что создать:**

1. `app-mobile/src/main/java/ru/mars/mobile/MemoryNoticePolicy.kt` — чистый JVM:

   ```kotlin
   /** Строка «запомнил» под ответом: что именно и когда. */
   data class MemoryNotice(
       val text: String,
       val at: Long,
   ) {
       val isVisible: Boolean get() = text.isNotBlank()
   }

   object MemoryNoticePolicy {
       /** Сколько живёт подсказка в ленте, мс. */
       const val LIFETIME_MS: Long = 60_000L

       /**
        * Разобрать результат вызова `remember` в строку для человека.
        * Возвращает null, если факт не записан (ошибка, пусто, дубль).
        */
       fun fromToolResult(result: String, nowMs: Long): MemoryNotice?

       /** Строка для ленты: «Запомнил: …», обрезанная до разумной длины. */
       fun render(notice: MemoryNotice): String

       /** Пора ли убрать подсказку из ленты. */
       fun isExpired(notice: MemoryNotice, nowMs: Long): Boolean

       /** Сколько фактов в памяти — для подписи «всего N»; null, если неизвестно. */
       fun countHint(count: Int): String
   }
   ```

   Правила, обязанные быть:
   - **Формат ответа инструмента (проверено координатором по `RememberTool.kt`, не выдумывай
     другой):** успех — строка `"Запомнил: <факт>"` (`toolOk("Запомнил: $fact")`),
     отказ — `"В память не записано: <причина>"` (`toolError(...)`), плюс отказы валидации
     вроде `"Не указан обязательный аргумент text…"`, `"Факт слишком длинный…"`.
     `fromToolResult` берёт факт из строки с префиксом `"Запомнил: "`; любую другую
     строку — `null`;
   - дубль и ошибка → `null` (подсказка «запомнил» на неудаче хуже её отсутствия);
   - `render` обрезает до 120 символов и добавляет `…`, если обрезал;
   - `isExpired`: `nowMs - notice.at >= LIFETIME_MS`.

2. `app-mobile/src/main/java/ru/mars/mobile/MemoryNoticeView.kt` — View-слой:
   ```kotlin
   internal class MemoryNoticeView(activity: Activity) {
       val root: View
       fun show(notice: MemoryNotice)
       fun hide()
       fun isShown(): Boolean
   }
   ```
   Тонкая полоска с иконкой-точкой `MarsTheme.STATUS_INFO`, текст в одну строку,
   `ellipsize = END`. Никакой анимации: появляется и висит, пока не истечёт.

**Приёмка потока C:** успешная запись → `isVisible`; ошибка → `null`; дубль → `null`;
`render` на 200 символах даёт ровно 120 + `…`; `isExpired` ровно на границе `LIFETIME_MS`.

---

## Поток D — ответ прямо из уведомления

**Зачем.** `PushNotifications` рисует `BigTextStyle` без действий: чтобы ответить,
человек открывает приложение. У ChatGPT есть «ответить» из шторки.

**Что создать (только чистые типы; правку `PushNotifications.kt` делает координатор):**

1. `app-mobile/src/main/java/ru/mars/mobile/PushReplyPolicy.kt` — чистый JVM:

   ```kotlin
   /** Ключи и подписи ответа из уведомления. */
   object PushReplyPolicy {
       const val ACTION_REPLY: String = "ru.mars.mobile.PUSH_REPLY"
       const val EXTRA_SESSION_ID: String = "push_session_id"
       const val EXTRA_TEXT: String = "push_reply_text"
       const val KEY_TEXT: String = "push_reply_input"
       const val LABEL_REPLY: String = "Ответить"
       const val LABEL_SEND: String = "Отправить"
       const val HINT: String = "Сообщение"

       /** Показывать ли действие ответа для этой сессии. */
       fun canReply(sessionId: String?, readOnly: Boolean, subagent: Boolean): Boolean

       /** Текст из уведомления: нормализация и защита от пустого. */
       fun normalizeReply(raw: String?): String?

       /** Что показать, если ответ отправить не удалось. */
       fun failureNotice(error: String?): String
   }
   ```

   Правила: пустой/пробельный текст → `null`; `readOnly` и `subagent` → `false`
   (ответ в такую сессию сервер отклонит); нормализация срезает края и сжимает
   повторные пробелы внутри.

**Приёмка потока D:** `canReply(null, …)` → `false`; `canReply("s1", false, false)` → `true`;
`canReply("s1", true, false)` → `false`; `normalizeReply("  а\n\n б  ")` → `"а б"`;
`normalizeReply("   ")` → `null`.

---

## Формат отчёта (обязателен, иначе работа не принимается)

- созданные/изменённые файлы с числом строк;
- что проверено и ЧЕМ (чтение, сверка символов, прогон логики на Python-зеркале);
- что НЕ проверено и почему (обязательно: «Gradle запрещён — компиляцию не видел»);
- типы, добавленные сверх контракта, с сигнатурами;
- **где контракт противоречил реальности** — это самое ценное в отчёте;
- список случаев для теста своего потока с точными ожиданиями.
