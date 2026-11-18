# Контракт Волны 22: говорящий орб + выбор голоса, который не подменяется молча

Дата: 18.11.2026. База: коммит `5d8ad225` (снимок зелёного дерева, `BUILD SUCCESSFUL`).
Ветка: `feat/local-chat-wave`. Источник истины: `C:\Users\SKY\.zcode\workspace\default\mars-android`.

## Жалоба владельца (дословно)

> «модели то молчат, то не меняются в настройках (точнее меняется визуально а по факту нет)…
> мне нужен работающий орб не уступающий по качеству чату гпт»

Речь про **голосовые модели (TTS)**, а не про LLM. Текстовые модели работают штатно, их
не трогаем вообще.

## Что координатор УЖЕ доказал фактами (не перепроверять, принимать как дано)

1. **Тракт выбора голоса собран верно.** `VoiceProsodySheet` → `VoiceProsody.voiceId` →
   `VoiceProsodyPrefs` (`KEY_VOICE = "voice_id"`) → `RepositoryServerTtsClient.chosenProfile()`
   → `ServerTtsVoices.byId()` → профиль. Потери настройки в этом пути НЕТ.
2. **Основной голос приложения — платный** `plusvibe-alice` (`ServerTtsVoices.PLUSVIBE_ALICE`):
   engine `plusvibe`, модель `elevenlabs-v4-tts`, голос `Alice`. Он уходит НЕ на сервер Марса,
   а напрямую провайдеру через `PlusVibeMediaTtsClient` (`POST {root}/api/media/generate`).
3. **Платный голос требует профиль PlusVibe ВНУТРИ ПРИЛОЖЕНИЯ** (`PlusVibeTtsAccess.bind()`).
   Нет профиля → `Unavailable("no_plusvibe_profile")` без единого сетевого запроса; нет ключа →
   `Unavailable("no_api_key")`. Дальше `PreferOfflineEngine.speakCloudFirst` молча уходит на
   резерв: Дмитрий (edge) → Piper → системный TTS.
4. **Симптом на телефоне подтверждён логом.** В `adb logcat` зафиксирован `AudioTrack` с
   `sampleRate=44100` и `Codec2Client` (декодирование) — это НЕ Piper (у него 22 050 Гц,
   `OfflineTtsManifest.SAMPLE_RATE`). Строки `offline tts ready` в логе нет.
5. **Орб во время речи Марса управляется синтетической синусоидой, а не звуком.**
   `LocalVoiceController.speechEnvelope` → `envelopeAt(speechPhase)` — три несоизмеримые
   синусоиды «похожие на фразы». Реальный RMS снимается ТОЛЬКО с микрофона
   (`publishMicLevel`), то есть пока говорит человек. Громкость вывода не измеряется нигде.
6. **`Log.d` из release на MIUI не доходит.** В `logcat` процесса ноль D-строк приложения
   при живом запросе. Диагностика обязана писать через `Log.i`.

## Три независимых дефекта (по одному на воркера)

| # | Дефект | Симптом владельца |
|---|---|---|
| 1 | Подмена голоса молча + отсутствие наблюдаемости | «не меняется, хотя выбрал» |
| 2 | Выбор голоса теряется на вторичных путях, кэш предсинтеза не знает голоса | «то молчат, то не меняются» |
| 3 | Орб не слышит собственный голос Марса | «не как в ChatGPT» |

## Разрез файлов — границы жёсткие

Один каталог/файл — один воркер. Строка «НЕ трогает» важнее строки «правит».

| Воркер | Правит | НЕ трогает |
|---|---|---|
| W1 «Наблюдаемость голоса» | `app-mobile/src/main/java/ru/mars/mobile/VoiceTrace.kt` (НОВЫЙ), `app-mobile/src/main/java/ru/mars/mobile/VoiceChannelReport.kt` (НОВЫЙ), `app-mobile/src/test/java/ru/mars/mobile/VoiceTraceTest.kt` (НОВЫЙ), `app-mobile/src/test/java/ru/mars/mobile/VoiceChannelReportTest.kt` (НОВЫЙ) | всё остальное |
| W2 «Выбор голоса» | `app-mobile/src/main/java/ru/mars/mobile/ServerTtsSpeechEngine.kt`, `app-mobile/src/main/java/ru/mars/mobile/PreferOfflineEngine.kt`, `app-mobile/src/main/java/ru/mars/mobile/PlusVibeTtsAccess.kt`, `core-network/src/main/java/ru/mars/network/repository/ServerTtsVoiceProfile.kt`, плюс их тесты (`ServerTtsSpeechEngineTest.kt`, `PreferOfflineEngineTest.kt`, `PlusVibeTtsAccessTest.kt`, `ServerTtsVoiceContractTest.kt`) | `VoiceTrace.kt`, `VoiceChannelReport.kt`, `LocalVoiceController.kt`, `LocalChatActivity.kt`, `AndroidServerTtsPlayback.kt` |
| W3 «Орб слышит голос» | `app-mobile/src/main/java/ru/mars/mobile/LocalVoiceController.kt`, `app-mobile/src/main/java/ru/mars/mobile/SpeechLevelTap.kt` (НОВЫЙ), `app-mobile/src/main/java/ru/mars/mobile/AndroidServerTtsPlayback.kt`, `app-mobile/src/main/java/ru/mars/mobile/tts/OfflineTtsBackend.kt` (ТОЛЬКО добавление геттера сессии), их тесты | `ServerTtsSpeechEngine.kt`, `PreferOfflineEngine.kt`, `PlusVibeTtsAccess.kt`, `VoiceTrace.kt`, `LocalChatActivity.kt` |

**Общие файлы — только координатор:** `LocalChatActivity.kt`, `LocalVoiceTts.kt`,
`LocalVoiceButton.kt`, `VoiceModeOverlay.kt`, `*.gradle.kts`, `AndroidManifest.xml`,
каталог версий, `LocalChatSettingsPolicy.kt`.

## Общие типы (написаны координатором; воркеры их НЕ переопределяют)

Пакет `ru.mars.mobile`, модуль `app-mobile`. Файл `VoiceChannelReport.kt` создаёт W1 —
сигнатуры ниже дословные, остальные воркеры на них ссылаются как на существующие.

```kotlin
package ru.mars.mobile

/** Каким каналом РЕАЛЬНО прозвучала реплика. */
internal enum class VoiceChannel {
    CLOUD_PAID,
    CLOUD_SERVER,
    OFFLINE_PIPER,
    SYSTEM_TTS,
    NONE,
}
```

```kotlin
package ru.mars.mobile

/**
 * Факт одной реплики: что выбрали, чем прозвучало, почему не выбранным.
 * Без текста реплики и без ключей.
 */
internal data class VoiceReport(
    val wantedProfileId: String?,
    val actualChannel: VoiceChannel,
    val actualProfileId: String?,
    val reason: String?,
    val atMs: Long,
) {
    /** Прозвучало ли ровно то, что человек выбрал. */
    val substituted: Boolean
        get() = reason != null || (wantedProfileId != null && actualProfileId != wantedProfileId)
}
```

```kotlin
package ru.mars.mobile

/**
 * Правда о голосе — в лог через Log.i (D-строки в release не доходят, проверено на POCO).
 * Одна строка на реплику, тег MarsVoice.
 */
internal object VoiceTrace {
    const val TAG: String = "MarsVoice"

    /** Подмена точки записи для JVM-тестов. `null` — писать в logcat. */
    var sink: ((String) -> Unit)? = null

    fun report(report: VoiceReport)

    /** Готова ли цепочка к речи: есть ли профиль PlusVibe, ключ, модель Piper. */
    fun readiness(paidReady: Boolean, piperReady: Boolean, serverReady: Boolean)
}
```

**Правила `VoiceTrace` (обязательные):**
- никакого текста реплики и никакого ключа в строке — только `profileId`, канал, причина, время;
- `Log.i`, НЕ `Log.d`;
- `sink` — точка подмены для JVM-теста (тот же приём, что в `MarsModelLog`).

## W1 — Наблюдаемость голоса

**Цель:** сделать «молчит / подменился» доказуемым фактом, а не спором.

Что сделать:
1. Создать `VoiceChannelReport.kt` и `VoiceTrace.kt` ровно по сигнатурам выше.
2. `VoiceTrace.report()` форматирует строку вида:
   `voice wanted=plusvibe-alice actual=CLOUD_SERVER(profile=natural-svetlana) reason=no_api_key substituted=true`
   — одним вызовом `Log.i(TAG, …)`, без переносов.
3. `VoiceTrace.readiness()` — одна строка состояния цепочки на старте разговора:
   `voice ready paid=true piper=false server=true`.
4. JVM-тесты (`VoiceChannelReportTest`, `VoiceTraceTest`) без Android:
   - `VoiceReport.substituted`: `true` при `reason != null`; `true` при смене профиля;
     `false` когда выбранный прозвучал им же;
   - `VoiceTrace` через `sink` пишет ОДНУ строку, содержит `wanted=`, `actual=`, `reason=`;
   - ни текст реплики, ни слово `apiKey`/`Bearer` в строку не попадают (передать в поля
     приватный маркер и убедиться, что его нет в выводе).

**Запрещено:** трогать чужие файлы; заводить свои типы вместо `VoiceChannel`/`VoiceReport`;
писать в `Log.d`; запускать Gradle.

## W2 — Выбор голоса не теряется и не подменяется молча

**Гипотеза (подтверди или опровергни по коду, не выполняй слепо):** выбор голоса теряется на
вторичных путях, а причина подмены не доходит до человека.

Что сделать:
1. **`ServerTtsSpeechEngine.setProsody` (строка ~330) сейчас только переливает значение в
   `fallback` и не сохраняет его для себя.** Проверить, теряется ли настройка на пути
   `LocalVoiceButton.speakNow` → контроллер → движок. Если да — сохранять в поле и пробрасывать
   в `RepositoryServerTtsClient` явной зависимостью (лямбдой-провайдером, не приведением типа).
   Метод `SpeechEngine.setProsody` в интерфейсе УЖЕ имеет тело по умолчанию (`= Unit`) —
   из этого следует, что любая реализация без `override` молча съедает вызов. Грепнуть
   `override fun setProsody` по всем реализациям и убедиться, что переопределено ВЕЗДЕ.
2. **Кэш предсинтеза индексируется только по тексту** (`prefetched.containsKey(text)`).
   Смена голоса посреди ответа оставляет остаток фраз старым голосом. Сделать ключ
   составным: `id профиля + текст`. При смене `voiceId` — отменять и очищать кэш.
3. **Причина подмены обязана быть видимой.** `onPaidUnavailable(reason)` и
   `onVoiceSubstituted` уже существуют — проверить, что каждый путь деградации их зовёт.
   Найти пути, где звук подменяется МОЛЧА, и закрыть их вызовом колбэка.
4. **Проблема «нет профиля PlusVibe».** `PlusVibeTtsProfile.select()` ищет профиль по домену
   (`plusvibe`). Проверить, что профиль, заведённый с адресом `https://plusvibeapi.ru/v1`,
   распознаётся (регистрируемая метка — `plusvibeapi`, начинается с `plusvibe` → да).
   Добавить в `PlusVibeTtsProfile` хелпер человеческой причины для UI:
   ```kotlin
   fun humanReason(reason: String?): String
   ```
   Возвращает короткую русскую фразу для `no_plusvibe_profile` / `no_api_key` / `timeout` /
   `transport` / прочих (fallback — сам код). Чистая функция, тестируется JVM.

**Критерий приёмки W2:** юнит-тесты на:
- `setProsody` доходит до клиента синтеза (тест на `RepositoryServerTtsClient`: после
  `setProsody(voiceId=X)` запрос уходит с профилем X, а не с дефолтным) — использовать
  существующий фейк-транспорт, сеть не нужна;
- кэш предсинтеза не отдаёт фразу, синтезированную другим голосом;
- `humanReason` возвращает осмысленный текст на все известные коды и сам код на неизвестный.

**Запрещено:** трогать `LocalChatActivity.kt`, `VoiceTrace.kt`, `LocalVoiceController.kt`,
`VoiceModeOverlay.kt`, Gradle; менять публичные сигнатуры существующих методов так, чтобы
ломались вызывающие (новые методы — только с телом по умолчанию).

## W3 — Орб слышит собственный голос Марса

**Цель:** планета пульсирует РЕАЛЬНОЙ громкостью вывода, как орб ChatGPT, а не осциллятором.

Что сделать:
1. Новый файл `SpeechLevelTap.kt`, пакет `ru.mars.mobile`. Порт + Android-реализация:

```kotlin
package ru.mars.mobile

/** Источник громкости ИГРАЮЩЕГО аудио (не микрофона). */
internal interface SpeechLevelSource {
    /** @return `false` — источник недоступен, вызывающий обязан остаться на резерве. */
    fun start(sessionId: Int, onLevel: (Float) -> Unit): Boolean
    fun stop()
}
```

   Реализация `VisualizerLevelSource(context)`:
   - `android.media.audiofx.Visualizer(sessionId)` с `captureSize` по
     `Visualizer.getCaptureSizeRange()` (максимум);
   - `setDataCaptureListener` → RMS по байтам waveform (8-бит без знака, центр 128);
   - нормализация в 0..1 с усилением (речь даёт малый RMS — взять усиление как у микрофона
     `MIC_LEVEL_GAIN`, но подобрать так, чтобы тихая речь давала 0.2–0.4, громкая — 0.8+);
   - ВСЕГДА `release()` в `stop()` и при исключении; ошибки (`RuntimeException` — эффект занят
     другим приложением) → `start` возвращает `false`, а не падает.
2. `LocalVoiceController`:
   - добавить поле `levelSource: SpeechLevelSource?` (необязательный параметр конструктора,
     дефолт `null` — тогда поведение прежнее, тесты не ломаются);
   - во время фазы SPEAKING брать уровень ИЗ `levelSource`, а синтетическую огибающую
     `speechEnvelope` оставить РЕЗЕРВОМ, включаемым только если `start()` вернул `false`;
   - сессию брать через провайдер `audioSessionProvider: (() -> Int?)? = null` (его даёт
     координатор из плеера/Piper);
   - обязательно `stop()` при `stopSpeaking`, смене фазы и `release()`.
3. `AndroidServerTtsPlayback`: отдать `audioSessionId` играющего ExoPlayer наружу геттером
   (`fun currentSessionId(): Int?`, `null` когда не играет). НЕ менять публичные методы.
4. `OfflineTtsBackend`: добавить геттер сессии `AudioTrack` (только чтение, существующую
   логику синтеза не трогать).

**Критерий приёмки W3:** юнит-тесты на политику выбора источника (чистая логика без Android):
- `start()` вернул `true` → уровень идёт из источника, огибающая не считается;
- `start()` вернул `false` → включается резервная огибающая, фаза SPEAKING не залипает;
- `stop()` вызывается при уходе из SPEAKING и при `release()`;
- битый уровень (NaN, ±∞, >1, <0) не ломает кадр: зажимается в 0..1.

**Запрещено:** рисовать орб заново, трогать `VoiceModeOrbView.kt` (он готов и проверен),
менять палитру/переходы фаз, лезть в `ServerTtsSpeechEngine.kt`.

## Общие запреты для всех воркеров

- **Не запускать Gradle/`gradlew`/`kotlinc`.** Компиляция — работа координатора. В отчёте
  писать честно «не собирал».
- **Не править файлы вне своей зоны** — даже одной строкой.
- **Не создавать общие типы** у себя: `VoiceChannel`, `VoiceReport`, `VoiceTrace`,
  `SpeechLevelSource` описаны здесь дословно.
- **Не трогать `LocalChatActivity.kt`** — весь клей делает координатор.
- `***` в выводе инструментов — это маскировка секрета системой, а не содержимое файла.
  Тип поля писать `String`.
- Не переименовывать существующие публичные методы: их зовут чужие файлы.
- Комментарии и KDoc — по-русски, в стиле проекта (объяснять ПОЧЕМУ, а не что).

## Отчёт воркера (обязательные пункты)

1. Созданные/изменённые файлы с числом строк.
2. Что проверено и ЧЕМ (чтение, javap, вынос чистой логики в тест).
3. Что НЕ проверено и почему.
4. Типы, добавленные сверх контракта, с сигнатурами.
5. Где контракт противоречил реальности кода.
6. Подтвердилась ли гипотеза из контракта — или опровергнута (это ценный результат).

## Что делает координатор после батча

1. Сборка `bash build-mars.sh :app-mobile:assembleRelease --no-daemon` + тесты.
2. Клей в `LocalChatActivity.kt`: `VoiceTrace.report` на каждый ход, `VoiceTrace.readiness`
   при входе в разговор, `audioSessionProvider` из плеера/Piper, плашка причины подмены
   через `PlusVibeTtsProfile.humanReason`.
3. Release-сборка, проверка `mapping.txt` на живость новых классов.
4. Установка на POCO `6d6fe9f6` и приёмка логом `adb logcat -s MarsVoice`.

## Итог волны 22 (записано координатором после сведения)

### Блокер нарезки — имя `VoiceChannel` было занято

Контракт объявлял новый `enum class VoiceChannel`, но в проекте УЖЕ существовал
`public enum class VoiceChannel { CLOUD, OFFLINE, SYSTEM }` в `PreferOfflineEngine.kt` (его
используют 7 файлов). Два объявления в одном пакете — ошибка компиляции.

**Решение:** новый тип переименован в `VoiceOutcomeChannel` (4 файла W1), старый не тронут.
На будущее: перед объявлением любого общего типа — `grep -rn "enum class <Имя>"` по всему репо,
а не только «есть ли имя в моей зоне».

### Найдено и починено сверх трёх задач

1. **`ServerTtsSpeechEngine.setProsody` не сохранял значение** и не пробрасывал его в клиент —
   голос доезжал только двумя обходными путями (`PreferOfflineEngine` + приведение типа в
   `LocalVoiceTts`). W2 добавил метод в ПОРТ `ServerTtsClient.setProsody(...)` с телом по
   умолчанию, и приведение типа стало не нужно.
2. **Кэш предсинтеза индексировался только по тексту** — остаток ответа звучал прежним голосом.
   Ключ стал `ServerTtsPrefetchKeys.of(voiceId, text)`, смена голоса/темпа чистит кэш.
3. **Три пути молчаливой подмены** закрыты колбэками (`onPaidUnavailable` не звался при уходе
   на серверную цепочку, понижение внутри цепочки не сообщалось вовсе, неизвестный `voiceId`
   уходил на Ирину молча).
4. **`ChatActivity.voiceProsody` объявлен и НИ РАЗУ не присвоен** — серверный чат всегда
   синтезировал дефолтным голосом. Починено: `VoiceProsodyPrefs(this).load()` в `onCreate`
   ДО `initializeVoiceStack()`.
5. **`PiperFallbackTtsClient` не переопределял `setProsody`** — потоковая озвучка глотала темп
   дефолтным телом порта (`= Unit`). Добавлен форвард (плюс импорт `VoiceProsody`).
6. **`publishLevel` зажимал значение через `coerceIn`, который пропускает `NaN`** — любой
   будущий источник уровня уронил бы кадр орба.

### Приёмка на устройстве (18.11, POCO F5, `6d6fe9f6`)

Release установлен, `versionCode 46`. Лог впервые дал ФАКТ:

```
I MarsVoice: voice ready paid=true piper=false server=true
```

**Платный голос PlusVibe настроен (профиль и ключ есть), Piper не скачан.** Значит «Алиса»
звучит по-настоящему; жалоба «выбрал — не тот голос» относилась к дефектам 1–5 выше, а не к
отсутствию настройки. Проверено «Озвучить сообщение»: звук пошёл через `ExoPlayer` +
`AudioTrack` (платный канал), подмены нет — поэтому строки `report` в логе и не было (она
фиксирует только деградацию).

Все шесть новых классов живы в release (`mapping.txt`): `VoiceTrace`, `VoiceChannelReport`,
`VisualizerLevelSource`, `SpeechLevelRouter`, `SpeechLevelMath`, `ServerTtsPrefetchKeys`.
`Visualizer` не дал ни одной ошибки в `logcat` (эффект занят не был).

### Что осталось непроверенным на устройстве

- **Пульсация орба от реального звука.** `Visualizer` ошибок не даёт, но визуально «планета
  захлёбывается на громком слове» подтверждается только глазами владельца — я вижу лишь
  отсутствие ошибок в логе.
- **Смена голоса в шторке → другой тембр.** Нужен ручной выбор двух разных голосов подряд и
  сравнение на слух: лог скажет `voice wanted=…`, только если была деградация.
- **`report` при подмене.** Ни один путь подмены за прогон не сработал (платный канал был жив),
  поэтому формат строки на устройстве ещё не наблюдался — только в JVM-тестах.

