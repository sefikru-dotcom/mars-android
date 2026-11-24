# Контракт волны 13 — «полировка под ChatGPT/Gemini» (локальный + серверный чат)

Основание: разбор ChatGPT/Gemini Live (см. переписку и `docs/POCKETPAL-TRANSFER.md`).
Состояние базы: ветка `feat/local-chat-wave`, коммит `053f6444`, дерево чистое,
`:app-mobile:assembleDebug` — BUILD SUCCESSFUL (проверено координатором 02.11.2026).

Волна — ЧЕТЫРЕ независимых потока. Воркер делает ТОЛЬКО СВОЙ поток.

| Поток | Что | Зона (единственная) |
|---|---|---|
| A | Правка своей реплики инлайн | `LocalChatEdit*`, `LocalChatActions*` |
| B | Темп речи и выбор голоса | `VoiceProsody*`, `VoiceProsodySheet.kt` |
| C | Полноэкранный голосовой режим | `VoiceMode*` |
| D | Умный конец реплики (semantic VAD) | `EndpointPolicy*` |

---

## Железные правила (нарушение = отказ работы)

1. **Не запускать Gradle, gradlew, kotlinc, javac.** Сборку делает только координатор.
   Проверять чтением, `javap` и выносом чистой логики в тестируемый вид.
2. **Не трогать общие файлы** (список в разделе «Чужая зона»). Это зона координатора.
3. **Не создавать типы в общих пакетах.** Новые общие типы уже созданы координатором
   (ниже, дословно). Свои вспомогательные типы держать `private`/`internal` в своих файлах.
4. **Не заводить новые зависимости** (`build.gradle.kts`, каталог версий — зона координатора).
5. **Публичные методы существующих классов не менять** — экран уже их вызывает.
6. Маскированные значения в выводе инструментов (`***`) — это редакция секрета, файл цел.
7. Все новые `data class`/политики — **чистый JVM** (без `android.*`), чтобы тестировались
   юнит-тестами. Android-зависимые View-классы — отдельными файлами.
8. Пользовательские тексты — по-русски, в стиле существующих (см. `LocalChatActions.label`).

## Общие типы, УЖЕ созданные координатором (читать, не переписывать)

Координатор добавил их до нарезки, они компилируются. Файлы:

### `app-mobile/src/main/java/ru/mars/mobile/VoiceProsody.kt`

```kotlin
package ru.mars.mobile

/** Темп речи — то, что человек реально крутит. Хранится как множитель, не как проценты. */
data class VoiceProsody(
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val voiceId: String? = null,
) {
    val isDefault: Boolean get() = speed == DEFAULT_SPEED && pitch == DEFAULT_PITCH && voiceId == null

    fun withSpeed(value: Float): VoiceProsody = copy(speed = clampSpeed(value))
    fun withPitch(value: Float): VoiceProsody = copy(pitch = clampPitch(value))
    fun withVoice(id: String?): VoiceProsody = copy(voiceId = id)

    /** Подпись для шапки шторки: «1.15×», «обычный темп». */
    fun speedLabel(): String
    /** `rate` для серверного `api/tts`: `+15%` из speed 1.15; null при 1.0. */
    fun serverRate(): String?
    /** `pitch` для серверного `api/tts`: `+10Hz` из pitch 1.10; null при 1.0. */
    fun serverPitch(): String?
    /** Поля Android `TextToSpeech.setSpeechRate`/`setPitch` — там множитель, не проценты. */
    fun systemSpeechRate(): Float
    fun systemPitch(): Float
    /** Множитель для `OfflineTtsBackend.speed`; вне [MIN_SPEED]..[MAX_SPEED] — зажимается. */
    fun offlineSpeed(): Float

    companion object {
        const val MIN_SPEED: Float = 0.5f
        const val MAX_SPEED: Float = 2.0f
        const val MIN_PITCH: Float = 0.6f
        const val MAX_PITCH: Float = 1.6f
        const val DEFAULT_SPEED: Float = 1.0f
        const val DEFAULT_PITCH: Float = 1.0f
        val STEP_CHOICES: List<Float> = listOf(0.75f, 0.9f, 1.0f, 1.15f, 1.3f, 1.5f)

        fun clampSpeed(value: Float): Float = value.coerceIn(MIN_SPEED, MAX_SPEED)
        fun clampPitch(value: Float): Float = value.coerceIn(MIN_PITCH, MAX_PITCH)
    }
}

/** Сохранение темпа между запусками. Порт, а не SharedPreferences: чистая JVM, тестируется фейком. */
interface VoiceProsodyStore {
    fun load(): VoiceProsody
    fun save(prosody: VoiceProsody)
}
```

### `app-mobile/src/main/java/ru/mars/mobile/VoiceModeState.kt`

```kotlin
package ru.mars.mobile

/** Что рисует полноэкранный голосовой режим. Фазы — уже существующий [LocalVoicePhase]. */
data class VoiceModeState(
    val phase: LocalVoicePhase = LocalVoicePhase.IDLE,
    val userLine: String = "",
    val assistantLine: String = "",
    val notice: String? = null,
    val canRetry: Boolean = false,
) {
    /** Цвет фазы: значение из [MarsTheme] передаёт экран, здесь только имя роли. */
    val tone: VoiceModeTone
        get() = when (phase) {
            LocalVoicePhase.IDLE -> VoiceModeTone.IDLE
            LocalVoicePhase.LISTENING -> VoiceModeTone.LISTENING
            LocalVoicePhase.THINKING -> VoiceModeTone.THINKING
            LocalVoicePhase.SPEAKING -> VoiceModeTone.SPEAKING
        }

    /** Орб дышит только там, где есть звук или ожидание. */
    val orbPulses: Boolean get() = phase != LocalVoicePhase.IDLE

    companion object {
        fun from(state: LocalVoiceState): VoiceModeState = VoiceModeState(
            phase = state.phase,
            assistantLine = "",
            userLine = state.partial,
            notice = state.notice,
        )
    }
}

enum class VoiceModeTone { IDLE, LISTENING, THINKING, SPEAKING }
```

### `app-mobile/src/main/java/ru/mars/mobile/EndpointPolicy.kt`

```kotlin
package ru.mars.mobile

/** Что решил детектор конца реплики. */
enum class EndpointDecision { KEEP_LISTENING, END_UTTERANCE }

/**
 * Признаки конца реплики, снятые по хвосту уже имеющегося PCM.
 * Ни одного `android.*`: считается на JVM, тестируется синтетикой.
 */
data class EndpointFeatures(
    /** Длительность тишины на хвосте, мс. */
    val silenceMs: Long,
    /** RMS последнего речевого чанка. */
    val lastSpeechRms: Int,
    /** RMS плато последних ~400 мс (средний уровень голоса). */
    val averageSpeechRms: Int,
    /** Последний распознанный текст (может быть пустым у потокового ASR). */
    val lastWords: String = "",
    /** Сколько мс прошло с последнего финального результата ASR. */
    val sinceLastFinalMs: Long = 0L,
)
```

---

## Поток A — правка своей реплики инлайн

**Проблема по факту (проверено координатором 02.11.2026):**
- `LocalChatAction.EDIT` объявлен и описан в `LocalChatActions` (`label`, `canEdit`,
  `contentDescription`), но **в лист действий не попадает никогда**: в
  `LocalChatActionsSheet.show` пункта для EDIT нет вовсе, а в
  `LocalChatActivity.onMessageLongPress` список собирается руками.
- В `LocalChatPresenter` нет ни `truncateFrom`, ни переотправки с заменой последней
  реплики. Есть только `send(...)` (строка 527).
- Итог для человека: «Изменить» не существует, хотя код выглядит готовым.

**Что сделать (файлы создаёшь ты):**

1. `app-mobile/src/main/java/ru/mars/mobile/LocalChatEditPolicy.kt` — чистая логика:
   ```kotlin
   data class LocalChatEditPlan(
       val messageId: String,
       val newText: String,
       val keepBefore: Int,
       val dropAfter: Int,
   ) { val isNoop: Boolean get() = newText.isBlank() }

   object LocalChatEditPolicy {
       const val TITLE: String = "Изменить сообщение"
       fun canEdit(item: LocalChatItem, streaming: Boolean): Boolean
       fun plan(items: List<LocalChatItem>, messageId: String, newText: String): LocalChatEditPlan?
       fun hint(plan: LocalChatEditPlan): String
   }
   ```
   Правила: править можно только `USER`-строку без инструмента, не во время стрима;
   `plan` возвращает null, если строки нет; `dropAfter` = число строк после неё;
   `hint` — «Будет удалено N сообщений» либо «Ответ будет перезапрошен» при `dropAfter == 0`.

2. `app-mobile/src/main/java/ru/mars/mobile/LocalChatEditSheet.kt` — View-слой:
   диалог `MarsDialog` в стилистике `LocalChatActionsSheet` (тот же `MarsDialog.pillStack`
   не нужен — нужен `EditText` + две пилюли). Публичный API **ровно такой**:
   ```kotlin
   internal object LocalChatEditSheet {
       fun show(activity: Activity, initialText: String, hint: String, onApply: (String) -> Unit)
   }
   ```
   `EditText` многострочный, автопрокрутка, кнопки «Отправить заново» / «Отмена».
   Пустой текст — кнопка неактивна (без мёртвых нажатий).

3. `app-mobile/src/main/java/ru/mars/mobile/LocalChatActionsSheet.kt` — **только правка
   этого файла**: добавить `canEdit: Boolean = false, onEdit: () -> Unit = {}` в `show(...)`
   (новые параметры строго с дефолтами, в КОНЕЦ списка, существующие не менять) и пункт
   `EDIT_LABEL = "Изменить"` в порядке: Копировать → Повторить → **Изменить** → Выбрать.

4. Что делает координатор, НЕ ты: правку `LocalChatPresenter` (`truncateFrom`),
   `LocalChatActivity.onMessageLongPress`, клей в `ChatActivity` для серверного чата.

**Приёмка потока A:** `LocalChatEditPolicyTest` — правка середины даёт `dropAfter > 0`;
правка последней реплики даёт `dropAfter == 0`; стрим запрещает правку; пустой текст
даёт `null`. Плюс недостижимость: `LocalChatActions.available(...)` для USER-строки
СОДЕРЖИТ `EDIT` — это уже так, не сломать.

---

## Поток B — темп речи и выбор голоса

**Проблема по факту:** `VoiceProsody` не существует, `setSpeechRate` не вызывается нигде,
пользователь не управляет ни голосом, ни темпом. При этом серверный `api/tts` УЖЕ умеет
`rate` и `pitch` (`ServerTtsVoiceProfile.rate/pitch`), а `OfflineTtsBackend` УЖЕ принимает
`speed` (строка 86, `lengthScale = LENGTH_SCALE / speed`). То есть возможность есть,
интерфейса нет.

**Что сделать:**

1. `app-mobile/src/main/java/ru/mars/mobile/VoiceProsodyPolicy.kt` — чистая логика:
   ```kotlin
   data class VoiceOption(
       val id: String,
       val label: String,
       val hint: String,
       val prosody: VoiceProsody,
   )

   object VoiceProsodyPolicy {
       const val TITLE: String = "Голос и темп"
       /** Список голосов: сначала естественный (edge), затем резерв (piper). */
       fun options(): List<VoiceOption>
       /** Выбранный вариант по сохранённому темпу; null — «как в вебе». */
       fun selected(prosody: VoiceProsody, options: List<VoiceOption> = options()): VoiceOption?
       /** Следующий/предыдущий темп из [VoiceProsody.STEP_CHOICES]. */
       fun stepUp(speed: Float): Float
       fun stepDown(speed: Float): Float
       /** Подпись ползунка: «1.15× — быстрее обычного». */
       fun speedHint(speed: Float): String
   }
   ```
   Список голосов берётся из `ServerTtsVoices` (Svetlana — основная, Irina — резерв).
   Никаких выдуманных движков: только те, что есть в `ServerTtsVoices` и системный TTS.

2. `app-mobile/src/main/java/ru/mars/mobile/VoiceProsodySheet.kt` — View-слой:
   ```kotlin
   internal object VoiceProsodySheet {
       fun show(activity: Activity, current: VoiceProsody, onApply: (VoiceProsody) -> Unit)
   }
   ```
   Стиль `MarsDialog`. Внутри: список голосов пилюлями (выбранная подсвечена
   `MarsTheme.ACCENT_BG`/`ACCENT_BORDER`), строка темпа с «−» / значение / «+» по
   `STEP_CHOICES`, плашка «Проверить» — синтез короткой фразы тем же путём.
   Проверку делает вызывающий: параметр `onPreview: ((VoiceProsody) -> Unit)? = null`,
   кнопка рисуется только при `!= null`.

3. **Не трогать** `AndroidSpeechEngine`, `ServerTtsSpeechEngine`, `OfflineTtsSpeechEngine`,
   `VoiceOutputController` — подключение делает координатор. Но в отчёте ОБЯЗАТЕЛЬНО
   напиши точные строки, куда это вставлять (файл:строка, что именно вызвать), потому что
   ты видишь реальные сигнатуры, а у координатора их в голове нет.

**Приёмка потока B:** `VoiceProsodyPolicyTest` — `serverRate(1.15f) == "+15%"`,
`serverRate(1.0f) == null` (дефолт не отправляем), `stepUp` упирается в `MAX_SPEED` и не
перескакивает, у `selected` при неизвестном `voiceId` — null, `options()` непустой и
первый — естественный голос.

---

## Поток C — полноэкранный голосовой режим

**Проблема по факту:** `LocalVoicePhase` (IDLE/LISTENING/THINKING/SPEAKING) и
`LocalVoiceState` уже есть и считаются, но голос живёт в композере — отдельного экрана нет.
`MarsGradients.glowRing` уже даёт ореол для орба.

**Что сделать:**

1. `app-mobile/src/main/java/ru/mars/mobile/VoiceModeOrbView.kt` — кастомная View:
   ```kotlin
   class VoiceModeOrbView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
       fun render(tone: VoiceModeTone, pulses: Boolean)
       fun setLevel(level: Float)   // 0f..1f — амплитуда микрофона, «дышит» по речи
   }
   ```
   Рисовать `Canvas`: круг с радиальным градиентом от `MarsTheme.ACCENT` к прозрачному,
   тонкая обводка `MarsTheme.ACCENT_BORDER`. Анимация — только `alpha`/радиус,
   **в `onDraw` ничего не аллоцировать**. При `pulses = false` — статичный, без
   `postInvalidateOnAnimation`. Отключаться при `Settings.Global.ANIMATOR_DURATION_SCALE == 0f`.
   Никаких `ValueAnimator` с бесконечным циклом в `onAttachedToWindow` без отмены в
   `onDetachedFromWindow` — утечка.

2. `app-mobile/src/main/java/ru/mars/mobile/VoiceModeActivity.kt` — экран:
   ```kotlin
   class VoiceModeActivity : Activity() {
       companion object {
           const val EXTRA_TRANSCRIPT: String = "transcript"
           fun intentFor(context: Context, transcript: Boolean = false): Intent
       }
   }
   ```
   Полноэкранный, фон `MarsTheme.BG`. Сверху крестик «свернуть», по центру орб,
   под ним строка фазы («Слушаю», «Думаю», «Говорю») цветом `MarsTheme.STATUS_*`,
   ниже — транскрипт последней реплики (если `EXTRA_TRANSCRIPT`), внизу кнопка
   «Стоп/Микрофон». Точка входа в голос — существующий `LocalVoice*`; **экран не создаёт
   своих движков и не открывает микрофон сам**: он только рисует `VoiceModeState` и
   отдаёт нажатия наружу через
   ```kotlin
   var callbacks: Callbacks? = null
   interface Callbacks { fun onMicTap(); fun onStopSpeech(); fun onClose(); fun onRetry() }
   ```
   Все поля необязательные, без `callbacks` экран рисует состояние и не падает.

3. `app-mobile/src/main/java/ru/mars/mobile/VoiceModePresenter.kt` — чистая логика
   отображения: `fun render(state: VoiceModeState, transcript: Boolean): VoiceModeScreen`
   с полями `phaseLabel: String`, `hint: String?`, `primaryLabel: String`,
   `tone: VoiceModeTone`. Русские подписи фаз — здесь, не в Activity.

**Приёмка потока C:** `VoiceModePresenterTest` — IDLE даёт «Нажмите, чтобы говорить» и
основную кнопку «Говорить»; SPEAKING даёт основную кнопку «Стоп»; LISTENING — «Слушаю…»;
`VoiceModeState.from(LocalVoiceState)` переносит `partial` в `userLine` и `notice`.
Ручная приёмка на телефоне — координатор.

---

## Поток D — умный конец реплики (semantic VAD)

**Проблема по факту:** конец реплики решает `SilenceDetector` по чистому RMS и
`hangoverMs` (по умолчанию 1.2 с). Пауза-раздумье обрывает фразу, а быстрый ответ ждёт
лишний слот. У ChatGPT/Gemini это решает модель по смыслу.

**Что сделать — только политика, микрофон не трогать:**

1. `app-mobile/src/main/java/ru/mars/mobile/EndpointPolicy.kt` — **дополнить** уже
   созданный файл (там `EndpointDecision`, `EndpointFeatures` уже есть; добавляешь
   `object EndpointPolicy`):
   ```kotlin
   object EndpointPolicy {
       /** Порог тишины, после которого вообще стоит думать о конце. */
       const val HARD_SILENCE_MS: Long = 1600L
       /** Минимальная тишина, при которой «короткое слово» может закончить реплику. */
       const val SOFT_SILENCE_MS: Long = 550L

       fun decide(features: EndpointFeatures, hangoverMs: Long = 1200L): EndpointDecision
       /** Последнее слово обрывает фразу «на полуслове»? Предлоги, союзы, артикли. */
       fun looksIncomplete(lastWords: String): Boolean
       /** Уверенность 0..1: насколько хвост похож на конец фразы. */
       fun confidence(features: EndpointFeatures): Float
       /** Объяснение решения для лога (без текста реплики). */
       fun reason(features: EndpointFeatures, decision: EndpointDecision): String
   }
   ```
   Правила, которые ОБЯЗАНЫ быть:
   - тишина ≥ `HARD_SILENCE_MS` → `END_UTTERANCE` всегда;
   - тишина ≥ `hangoverMs` и `looksIncomplete == false` → `END_UTTERANCE`;
   - тишина ≥ `SOFT_SILENCE_MS`, но < `hangoverMs` → конец, **только** если последний
     RMS заметно ниже среднего (голос «упал», человек закончил мысль): `lastSpeechRms <
     averageSpeechRms * 0.55`;
   - `looksIncomplete == true` → `KEEP_LISTENING`, пока не выйдет `HARD_SILENCE_MS`;
   - `lastWords` пустой → опираться только на RMS (потоковый ASR может не дать текста).
   `looksIncomplete`: последнее слово из списка предлогов/союзов/частиц
   («и», «а», «но», «или», «что», «как», «если», «потому», «который», «в», «на», «с»,
   «для», «по», «при», «чтобы»), либо текст кончается на «,», «—», «-», «:».

2. **Не трогать** `SilenceDetector`, `VoiceRecorder`, `SpeechToText`, `StreamingStt`,
   `HandsFreeCapture`. Подключение — координатор.

**Приёмка потока D:** `EndpointPolicyTest` — «Сколько стоит билет,» → `KEEP_LISTENING`
при 600 мс тишины; «Сколько стоит билет» при 600 мс и падении RMS → `END_UTTERANCE`;
при 1700 мс → `END_UTTERANCE` даже с предлогом; пустой `lastWords` при 1300 мс → конец;
`confidence` растёт с тишиной и с падением уровня (монотонность проверить двумя точками).

---

## Чужая зона — НЕ ТРОГАТЬ ни одному воркеру

```
LocalChatActivity.kt        LocalChatPresenter.kt      LocalChatTranscript.kt
ChatActivity.kt             ChatInitSection.kt         SettingsActivity.kt
MainActivity.kt             MarsTheme.kt               MarsDialog.kt
MarsGradients.kt            AndroidSpeechEngine.kt     ServerTtsSpeechEngine.kt
OfflineTtsSpeechEngine.kt   VoiceOutputController.kt   LocalVoiceController.kt
LocalVoiceTts.kt            LocalVoiceButton.kt        SilenceDetector.kt
VoiceRecorder.kt            SpeechToText.kt            StreamingStt.kt
HandsFreeCapture.kt         LocalVoiceState.kt         LocalVoicePhase (в LocalVoiceState.kt)
*.gradle.kts, libs.versions.toml, gradle/*, AndroidManifest.xml
```

Манифест нужен потоку C — **координатор сам** добавит `VoiceModeActivity` в манифест.
Тебе писать `Activity` так, чтобы она работала после этой строки.

## Формат отчёта (обязателен, иначе работа не принимается)

- созданные/изменённые файлы с числом строк;
- что проверено и ЧЕМ (чтение, javap, ручной прогон логики);
- что НЕ проверено и почему (особенно: «Gradle запрещён — компиляцию не видел»);
- типы, добавленные сверх контракта, с сигнатурами;
- для потока B — точные `файл:строка`, куда координатору вставлять вызовы;
- где контракт противоречил реальности (это самое ценное).
