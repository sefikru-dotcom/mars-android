# Контракт волны 4: закрыть разрывы, из-за которых голос молчит

Приёмка волны 2/3 на POCO показала: чат работает (пилюля `2.6k / 1M · 0%`, каталог
моделей, лист действий). Но в голосовом тракте **три разрыва**, из-за которых
фича не может заработать ни разу. Это не «не хватает удобства» — это обрыв
цепочки в середине.

## §1. Разрывы (проверено грепом на диске, не по отчётам)

| # | Что | Симптом для человека |
|---|---|---|
| 1 | `LocalVoiceEngine.startWakeWord()` зовёт `capture.start(listener = …)` **без `rawPcm`** | Микрофон открывается, но звук **не идёт в ASR** — распознавание не работает никогда |
| 2 | `WakeWordDetector` нигде не используется | Ключевая фраза «Марс, слушай» не срабатывает: детектор написан, но не подключён |
| 3 | `OfflineTtsPort.prepare()` **не вызывается никем** | Офлайн-синтез молчит: модель не скачивается, `isReady()` всегда false |
| 4 | `VoiceSessionPolicy` не подключён к `MarsVoiceService` | Микрофон откроется во время звонка и на 10% заряда |

**Причина 1 подробнее.** `HandsFreeCapture.start(listener, rawPcm)` принимает
второй необязательный параметр `RawPcmListener`, через который идёт непрерывный
поток PCM в потоковый ASR. Без него захват режет звук на сегменты для whisper-пути
и в `StreamingStt` ничего не попадает. В `LocalVoiceEngine` передан только
`listener` — то есть ASR глухой.

**Причина 2 подробнее.** Для фразы-активатора нужен детектор, который слушает
сырой PCM и решает «это ключевая фраза». `asr/WakeWordDetector.kt` существует и
принимает `Context`, у него есть `start/stop/state`. Он просто не создаётся:
`LocalVoiceEngine` его не упоминает.

## §2. Что уже есть (использовать как есть, не переписывать)

| Компонент | Файл | API |
|---|---|---|
| Захват микрофона | `HandsFreeCapture.kt` | `start(listener: Listener, rawPcm: RawPcmListener? = null): Boolean`, `stop()`, `cancel()`, `isCapturing`; `RawPcmListener.onRawPcm(pcm: ByteArray, length: Int)` — буфер ПЕРЕИСПОЛЬЗУЕТСЯ, копировать при необходимости |
| Потоковый ASR | `StreamingStt.kt` | `startUtterance()`, `acceptPcm(pcm, length)`, `finishUtterance(): String?`, `cancelUtterance()`, `events`, `isReady`, `load()` |
| Детектор фразы | `asr/WakeWordDetector.kt` | конструктор от `Context`, `start/stop/reset/isListening/state` |
| Политика сессии | `voice/VoiceSessionPolicy.kt` | `gate(env, nowMs): VoiceSessionDecision`, `tick(env, nowMs)`, `VoiceSessionEnvironment(batteryPercent, charging, screenOn, keyguardLocked, audioRoute, doNotDisturb, powerSaveMode, micPermissionGranted, modelInstalled, callActive, otherCaptureActive, foregroundServiceRunning)` |
| Офлайн-синтез | `tts/OfflineTtsPort.kt` | `suspend fun prepare(): Boolean`, `speak(text): Boolean`, `stop()`, `isReady()`, `release()` |
| Сервис | `service/MarsVoiceService.kt` | `VoiceEnginePort` (attach/startWakeWord/stopWakeWord/beginUtterance/endUtterance/speak/stopSpeaking/release), `VoiceEngineFactory`, `installEngineFactory`, `start/stop/deliverAnswer/isRunning` |
| Адаптер | `LocalVoiceEngine.kt` | текущая реализация (ПРАВИТСЯ в этой волне) |

## §3. Нарезка

### W1 — оживить микрофон: PCM в ASR и детектор фразы
**Правит:** `LocalVoiceEngine.kt` (единственный файл).
**НЕ трогает:** всё остальное.

Задачи:
1. В `startWakeWord()` передавать второй аргумент `rawPcm` в `capture.start(...)`:
   поток PCM → детектор ключевой фразы.
2. Создать и держать `WakeWordDetector` (конструктор от `Context`); в сыром PCM
   кормить его; по срабатыванию — `events?.onWakeWordDetected(phrase)`, затем
   переключиться в режим реплики.
3. В `beginUtterance()` — тот же захват продолжает идти, PCM идёт уже в
   `stt.acceptPcm(...)`, а не в детектор. Режим переключается флагом, микрофон не
   переоткрывается (иначе щелчок и потерянный первый слог).
4. `endUtterance()` — `stt.finishUtterance()`, текст в `events.onUtterance`.
5. Отмена/`stopWakeWord` — сбросить и детектор, и реплику.
6. Всё, что приходит с потока чтения микрофона, наружу отдавать как есть: сервис сам
   переносит на main (зафиксировано в его контракте).

**Обязательно проверить грепом после правки:** `rawPcm` присутствует в вызове
`capture.start`, `WakeWordDetector` упомянут как код, а не как комментарий.

### W2 — подключить правила безопасности к сервису
**Правит:** `LocalVoiceEngine.kt` (только конструктор и вызовы политики) **ИЛИ**
создаёт новый `voice/VoiceGate.kt`, если так чище. Файл `voice/VoiceSessionPolicy.kt`
НЕ менять.
**НЕ трогает:** `MarsVoiceService.kt`, `tts/`, `asr/`.

Задачи:
1. Собрать `VoiceSessionEnvironment` из реальных источников: `BatteryManager`,
   `PowerManager.isInteractive/isPowerSaveMode`, `KeyguardManager.isKeyguardLocked`,
   `NotificationManager.currentInterruptionFilter` (DND), `AudioManager.mode`
   (звонок), `AudioManager.getDevices` (маршрут), `checkSelfPermission(RECORD_AUDIO)`.
2. Перед `startWakeWord()` спрашивать `policy.gate(env, now)`; при `Deny` — вернуть
   `false` и отдать причину через `events.onFailed(reason)` **текстом из политики**
   (`VoiceSessionRejection.notice`), не выдумывать свой.
3. Периодически (раз в ~10 с, пока микрофон открыт) звать `policy.tick(env, now)`;
   при закрытии — `stopWakeWord()` и `events.onFailed(...)`.
4. Если микрофон обязан остаться открытым (`keepsMicOpen = true`), не закрывать его —
   так политика выражает «ждём под фразой-активатором».

### W3 — подготовка офлайн-синтеза
**Создаёт:** `tts/OfflineTtsWarmup.kt` (или метод в существующем классе — но НЕ в
`OfflineTtsBackend`, его не править).
**НЕ трогает:** `tts/OfflineTtsBackend.kt`, `tts/OfflineTtsManifest.kt`,
`LocalChatActivity.kt` (правку в экран делает координатор).

Задачи:
1. Функция/класс, который зовёт `suspend fun prepare()` у `OfflineTtsPort` **один
   раз** и запоминает результат (не качать при каждом «Озвучить»).
2. Прогресс скачивания (21 МБ) — наружу колбэком `(downloaded, total) -> Unit`,
   чтобы экран показал «Готовлю голос…».
3. Ошибка (нет сети, хеш не сошёлся) — не глотать: вернуть причину строкой,
   пригодной для показа человеку.
4. Повторный вызов после успеха — мгновенный `true` без сети.

## §4. Запреты

- **Не запускать Gradle.** Сборка только у координатора.
- **Не трогать git.**
- **Не добавлять зависимости.**
- **Не править** `AndroidManifest.xml`, `res/`, тесты, чужие файлы из списка «НЕ трогает».
- **Не переписывать** `VoiceSessionPolicy.kt`, `MarsVoiceService.kt`,
  `HandsFreeCapture.kt`, `StreamingStt.kt`, `asr/*` — они готовы.
- **Не выдумывать успешный прогон.** «Не проверял» — правильный ответ.

## §5. Критерии приёмки (проверяет координатор на POCO F5)

1. Грепом: `rawPcm` есть в `capture.start`, `WakeWordDetector` — живой код.
2. Грепом: `policy.gate(...)` вызывается перед `startWakeWord`.
3. Грепом: `prepare()` вызывается из не-tts кода.
4. Release-сборка: `WakeWordDetector`, `VoiceSessionPolicy` присутствуют в
   `mapping.txt` (в прошлой волне их там не было — R8 вырезал).
5. На телефоне: «Озвучить» говорит без сети; фраза-активатор срабатывает;
   при включённом «не беспокоить» микрофон не открывается.
