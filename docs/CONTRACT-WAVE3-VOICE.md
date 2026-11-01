# Контракт волны 3: офлайн-синтез и разговор при заблокированном экране

Волна 2 закрыла паритет локального чата с серверным. Остаток плана владельца —
«потом делаем офлайн синтез и разговор при заблокированном экране».

## §1. Что уже есть (НЕ переписывать)

| Компонент | Файл | Что даёт |
|---|---|---|
| sherpa-onnx AAR | `app-mobile/build.gradle.kts:163+` | нативный ASR/TTS-движок, `.so` для 4 ABI |
| Загрузчик моделей | `asr/ModelDownloader.kt` | скачивание tar.bz2 с проверкой SHA-256, атомарная установка |
| Манифест ASR-модели | `asr/AsrModelManifest.kt` | `MODEL_ID`, `ARCHIVE_URL`, `ARCHIVE_SHA256`, `ARCHIVE_SIZE`, `FILES`, `FILE_SHA256` |
| Хранилище моделей | `asr/ModelStore.kt` | `isInstalled()`, `sha256()`, `newStagingDir()`, `verify()`, `installFrom()` |
| ASR-бэкенд | `asr/SherpaAsrBackend.kt` | потоковое распознавание |
| Порт ASR | `AsrEnginePort.kt` | `StreamingAsrEnginePort.create(context)` |
| Голосовой модуль | `LocalVoice*.kt` (волна 2) | микрофон → композер, ответ → TTS |
| Образец foreground-сервиса | `service/MarsMusicService.kt` | `ServiceCompat.startForeground`, канал, политика уведомления |
| Озвучка | `AndroidServerTtsPlayback.kt`, `ServerTtsSpeechEngine.kt`, `VoiceOutputController.kt` | серверный синтез + системный TTS + фильтрация markdown |

**Чего НЕТ и что нужно создать:**
1. Локального (офлайн) TTS: модели Piper/VITS на диске, её манифеста и бэкенда.
2. Foreground-сервиса с типом `microphone` для разговора при погашенном экране.
3. Wake-word пути в фоне (детектор есть — `asr/WakeWordDetector.kt`, `WakeWordPolicy.kt`).

## §2. Факты об окружении (проверено, не выдумывать)

- `minSdk 28`, `compileSdk 34`, `targetSdk 34`. Классические View, без Compose.
- В манифесте уже объявлены: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`,
  `RECORD_AUDIO`, `POST_NOTIFICATIONS`.
- **Для микрофона в фоне нужны ДОПОЛНИТЕЛЬНО** (Android 14+):
  `FOREGROUND_SERVICE_MICROPHONE` и `android:foregroundServiceType="microphone"`.
  Без них `startForeground` бросит `SecurityException` на Android 14+.
- sherpa-onnx AAR уже в зависимостях — новых зависимостей добавлять НЕ надо.

### Сигнатуры sherpa-onnx v1.13.8 (сняты javap САМОЙ AAR, не из документации)

AAR: `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8`. Пакет `com.k2fsa.sherpa.onnx`.

```java
// com.k2fsa.sherpa.onnx.OfflineTts
public OfflineTts(android.content.res.AssetManager, OfflineTtsConfig);
public final int sampleRate();
public final int numSpeakers();
// Один синтез целиком. len — множитель скорости (1.0f), speakerId — индекс голоса.
public final GeneratedAudio generate(java.lang.String text, int speakerId, float speed);
// Синтез кусками: callback получает float[]-кусок PCM и возвращает код (0 — продолжать).
public final GeneratedAudio generateWithCallback(
    java.lang.String text, int speakerId, float speed,
    kotlin.jvm.functions.Function1<? super float[], java.lang.Integer> callback);
public final void release();
public final void free();

// com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
// Порядок аргументов: model, lexicon, tokens, dataDir, dictDir, noiseScale, noiseScaleW, lengthScale
public OfflineTtsVitsModelConfig(
    String model, String lexicon, String tokens, String dataDir, String dictDir,
    float noiseScale, float noiseScaleW, float lengthScale);

// com.k2fsa.sherpa.onnx.OfflineTtsConfig — собирается через Companion/builder;
// прочитать поля через javap: com.k2fsa.sherpa.onnx.OfflineTtsConfig
```

**Важно:** `generateWithCallback` — предпочтительный путь: PCM приходит кусками
`float[]`, их можно сразу отдавать в `AudioTrack` и начинать речь до окончания синтеза.
`generate` возвращает `GeneratedAudio` целиком — для длинного ответа это память.

**Прочитать остальные сигнатуры самому** перед началом:
`javap -classpath classes.jar com.k2fsa.sherpa.onnx.OfflineTtsConfig`
и `com.k2fsa.sherpa.onnx.GeneratedAudio`. Перепроверять документацию в интернете
не нужно и вредно — версия AAR может отличаться от публичной.

### Русская TTS-модель: скачана и посчитана координатором (НЕ выдумывать заново)

| Поле | Значение |
|---|---|
| `MODEL_ID` | `vits-piper-ru_RU-irina-medium-int8` |
| `ARCHIVE_URL` | `https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-irina-medium-int8.tar.bz2` |
| `ARCHIVE_SIZE` | `21_149_417` байт (проверено `ls -l`) |
| `ARCHIVE_SHA256` | `b0000a509f7551a80742eed5c43b8eb03f469cb9f0a42f6feee96ce0da0ebab8` — **64 символа** |
| Голос | женский (`irina`), 1 спикер, 22 050 Гц, качество medium |

Состав архива: **359 файлов**, из них:
- `ru_RU-irina-medium.onnx` — 18 579 710 байт (сам синтезатор)
- `tokens.txt` — 899 байт
- `MODEL_CARD` — 271 байт
- `espeak-ng-data/` — **355 файлов** (словари произношения; нужны целиком, иначе
  русский текст читается по буквам)

**Ловушка:** `espeak-ng-data` — это дерево каталогов, а не плоский список. Существующий
`ModelStore` построен на `Map<String, Long>` файлов в одном каталоге и **не подойдёт
для неё как есть**. Если используешь `ModelDownloader`, опиши в отчёте, как обошёл
вложенность (либо распаковка целиком без манифеста по файлам, с проверкой SHA только
архива). Не ломай `ModelStore` для ASR — он работает.

**Lexicon у этой модели НЕТ** (`lexicon` в `OfflineTtsVitsModelConfig` оставить пустым).
`dataDir` указывает на распакованный `espeak-ng-data`, `tokens` — на `tokens.txt`.

## §3. Нарезка

### W1 — офлайн TTS-бэкенд
**Создаёт:** `app-mobile/src/main/java/ru/mars/mobile/tts/OfflineTtsManifest.kt`,
`tts/OfflineTtsBackend.kt`, `tts/OfflineTtsPort.kt`.
**НЕ трогает:** ничего существующего.

Задачи:
1. `OfflineTtsManifest` — по образцу `AsrModelManifest`: `MODEL_ID`, `ARCHIVE_URL`
   (публичный HTTPS на HuggingFace или GitHub Releases, русская модель Piper/VITS),
   `ARCHIVE_SHA256`, `ARCHIVE_SIZE`, `FILES`, `FILE_SHA256`. **Размер файла и SHA-256
   обязаны быть настоящими**: скачай архив и посчитай сам, не выдумывай.
2. `OfflineTtsPort` — интерфейс без `android.*`: `speak(text: String): Boolean`,
   `stop()`, `isReady(): Boolean`, `release()`, `suspend fun prepare(): Boolean`.
3. `OfflineTtsBackend` — реализация на `OfflineTts` из sherpa-onnx. Синтез в
   `ByteArray` PCM, воспроизведение через `AudioTrack` (стримингово, не в память целиком).
4. Загрузка модели через существующий `ModelDownloader` + `ModelStore` — не писать второй
   загрузчик. Если сигнатуры не подходят под TTS-архив, опиши в отчёте, не копируй код.

### W2 — foreground-сервис разговора
**Создаёт:** `app-mobile/src/main/java/ru/mars/mobile/service/MarsVoiceService.kt`,
`service/VoiceNotificationPolicy.kt`.
**НЕ трогает:** `AndroidManifest.xml` (правит координатор), `MarsMusicService.kt`,
`LocalVoice*.kt`.

Задачи:
1. `MarsVoiceService` — по образцу `MarsMusicService`: `ServiceCompat.startForeground`
   в первые 5 секунд, канал уведомления, `START_NOT_STICKY`.
2. `VoiceNotificationPolicy` — чистая политика: тексты уведомления для состояний
   (слушаю/думаю/говорю), actions (Стоп, Открыть), иконки. Без `android.*`.
3. Сервис держит голосовой цикл: wake-word → ASR → (колбэк в приложение) → TTS.
   **Сам диалог с провайдером сервис не ведёт** — он только голос; ответ приходит
   через колбэк от приложения.
4. Обязательно: `onDestroy` освобождает микрофон и TTS; уведомление снимается.
5. Учесть, что Android 14+ запрещает `startForegroundService` из фона без причины —
   старт только из активного экрана (кнопка), описать это в KDoc.

### W3 — wake-word в фоне и политика батареи
**Создаёт:** `app-mobile/src/main/java/ru/mars/mobile/voice/VoiceSessionPolicy.kt`
(чистая), `voice/VoiceSessionController.kt`.
**НЕ трогает:** `asr/WakeWordDetector.kt`, `asr/WakeWordPolicy.kt` (использовать как есть),
`LocalVoice*.kt`, сервис из W2.

Задачи:
1. `VoiceSessionPolicy` — чистая: когда микрофон вообще можно держать открытым
   (заряд, экран, маршрут аудио, режим «не беспокоить»), таймауты простоя,
   лимит длительности одной сессии. Без `android.*`, тестируется на JVM.
2. `VoiceSessionController` — связывает wake-word детектор с сервисом W2 и TTS из W1.
3 `LocalVoiceController` (волна 2) НЕ менять; новый контроллер — отдельный путь для фона.

## §4. Запреты (все воркеры)

- **Не запускать Gradle.** Сборка только у координатора.
- **Не трогать git.**
- **Не добавлять зависимости** в `*.gradle.kts` / `libs.versions.toml`.
- **Не править** `AndroidManifest.xml`, `res/`, `strings.xml`, тесты, чужие файлы.
- **Не выдумывать SHA-256 и размеры архивов** — посчитать по реально скачанному файлу.
- **Не создавать второй загрузчик моделей** — использовать `ModelDownloader`/`ModelStore`.
- **Не заявлять проверку, которой не было.** «Не проверял» — правильный ответ.

## §5. Критерии приёмки

1. Офлайн-синтез говорит по-русски **без сети** (включить авиарежим — речь есть).
2. Модель скачивается один раз, SHA-256 сходится, повторный запуск не качает снова.
3. Сервис стартует из активного экрана, показывает уведомление, не падает на Android 15.
4. При заблокированном экране: wake-word → вопрос → ответ голосом.
5. После `onDestroy` микрофон закрыт (проверить, что индикатор записи погас).
6. Батарея: за 30 минут фонового ожидания расход не больше нескольких процентов.
