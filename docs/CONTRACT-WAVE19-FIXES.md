# Контракт Волны 19: Исправление 4 проблем после приёмки Волны 18

Ветка: `feat/local-chat-wave` (`C:/Users/SKY/.zcode/workspace/default/mars-android`).
**Запрет:** Воркерам КАТЕГОРИЧЕСКИ ЗАПРЕЩЕНО запускать `./gradlew`, `bash build-mars.sh`, `kotlinc` или любые компиляторы. Компиляцию, тесты и сборку делает только координатор.

---

## Разделение зон ответственности (строго по файлам)

| Воркер | Разрешённые файлы | Запрещено трогать |
|---|---|---|
| **Worker 1 (Голоса и выбор Светлана/Ирина)** | `core-network/src/main/java/ru/mars/network/repository/ServerTtsVoiceProfile.kt`, `app-mobile/src/main/java/ru/mars/mobile/ServerTtsSpeechEngine.kt`, `app-mobile/src/main/java/ru/mars/mobile/SpeechVoiceProfileSelector.kt`, `app-mobile/src/main/java/ru/mars/mobile/PreferOfflineEngine.kt`, `app-mobile/src/main/java/ru/mars/mobile/VoiceProsodyPolicy.kt`, `app-mobile/src/test/java/ru/mars/mobile/ServerTtsSpeechEngineTest.kt`, `app-mobile/src/test/java/ru/mars/mobile/VoiceProsodyPolicyTest.kt` | `LocalVoiceController.kt`, `VoiceModeOrbView.kt`, `LocalChatTranscript.kt`, `LocalChatActivity.kt` |
| **Worker 2 (Защита от самоперебивания орба)** | `app-mobile/src/main/java/ru/mars/mobile/LocalVoiceController.kt`, `app-mobile/src/main/java/ru/mars/mobile/LocalVoiceTts.kt`, `app-mobile/src/main/java/ru/mars/mobile/VoiceDialogLoop.kt`, `app-mobile/src/test/java/ru/mars/mobile/LocalVoiceControllerTest.kt` (или новый тест) | `VoiceModeOrbView.kt`, `LocalChatTranscript.kt`, `ServerTtsSpeechEngine.kt`, `LocalChatActivity.kt` |
| **Worker 3 (Реалистичная планета из заставки в Орбе)** | `app-mobile/src/main/java/ru/mars/mobile/VoiceModeOrbView.kt`, `app-mobile/src/main/java/ru/mars/mobile/MarsSpaceBackgroundView.kt` (только чтение), `app-mobile/src/main/res/drawable-nodpi/mars_orb_planet.webp` (если нужен кроп из `mars_bg.webp`) | Все остальные файлы |
| **Worker 4 (Время сообщения и быстрые кнопки в карточках)** | `app-mobile/src/main/java/ru/mars/mobile/LocalChatTranscript.kt`, `app-mobile/src/main/java/ru/mars/mobile/LocalChatPresenter.kt`, `app-mobile/src/main/java/ru/mars/mobile/ChatMessageCardRenderer.kt`, `app-mobile/src/test/java/ru/mars/mobile/LocalTurnFooterTest.kt` | `LocalVoiceController.kt`, `VoiceModeOrbView.kt`, `ServerTtsSpeechEngine.kt`, `LocalChatActivity.kt` |

Координатор после завершения воркеров сводит склейку в `LocalChatActivity.kt` (передача `serverTts` в `LocalVoiceButton`, колбэк `onReply`/`onSpeak` в `LocalChatTranscript`) и запускает сборку.

---

## Детальное задание по потокам

### Поток 1 (Worker 1): Починка смены голосов (Светлана / Ирина / Дмитрий) и качества голоса
**Корневые причины в коде сейчас:**
1. В `RepositoryServerTtsClient.synthesize(text)` (файл `ServerTtsSpeechEngine.kt`, строки 301–340) берётся `val candidates = selector.candidates()`, а выбранный пользователем голос `prosody.voiceId` (из `prosodyProvider?.invoke()?.voiceId ?: this.prosody?.voiceId`) **вообще игнорируется**! Метод `withProsody()` (строка 378) копирует только `rate` и `pitch`, но НЕ учитывает `chosen.voiceId`. В итоге что бы пользователь ни выбрал в шторке (Светлану или Ирину), `candidates()` всегда идёт по дефолтному порядку.
2. В `PreferOfflineEngine.kt` (строки 27–34) `speak(text)` **безусловно** вызывает `offline.speak(body)` первым и никогда не вызывает `fallback` (где живёт `ServerTtsSpeechEngine` со Светланой), если локальный Piper (`irina`) готов! При этом `PreferOfflineEngine.setProsody(prosody)` не сохраняет выбранный `prosody.voiceId`. Если человек выбрал сетевой голос (`natural-svetlana` или `natural-dmitry`, или `voiceId == null` «Как в вебе»), `PreferOfflineEngine` обязан сначала вызывать `fallback.speak(body)` (серверный TTS со Светланой), а локальный Piper (`offline.speak(body)`) использовать только когда выбран локальный профиль (`local-irina`) ИЛИ когда `fallback.speak(body)` вернул `false`!
3. Добавь в `ServerTtsVoices` (`ServerTtsVoiceProfile.kt`) также живой мужской нейросетевой голос `NATURAL_DMITRY` (`id = "natural-dmitry"`, `engine = "edge"`, `voice = "ru-RU-DmitryNeural"`, `rate = "+5%"`, `natural = true`, `needsNetwork = true`), включи его в `VOICE_DIALOG` вторым (после `NATURAL_SVETLANA`, перед `LOCAL_IRINA`), при этом сохрани все существующие тесты (`VOICE_DIALOG.first()` = `NATURAL_SVETLANA`, `ServerTtsVoices.byId("local-irina")` работает). В `VoiceProsodyPolicy.kt` в `option(profile)` добавь человеческую подпись для `natural-dmitry`: `"Дмитрий" to "Мужской голос веба, нужна сеть"`.
4. В `RepositoryServerTtsClient`: если `effectiveProsody.voiceId != null`, и `ServerTtsVoices.byId(effectiveProsody.voiceId)` найден — ставить этот профиль **первым** в списке кандидатов на синтез (перед остальными из `selector.candidates()`, без дублей), причём если пользователь явно выбрал голос, дать ему попытку даже если профиль был на временном cooldown (`selector.reset()` при смене `voiceId` в `setProsody`).

### Поток 2 (Worker 2): Защита орба от самоперебивания и ответа самому себе
**Корневые причины в коде сейчас:**
1. В `LocalVoiceController.kt`:
   - В `onTtsSpeakingChanged(speaking: Boolean)` (строки 1292–1314): когда TTS начинает говорить (`speaking = true`), если микрофон ещё был открыт (`capture?.isCapturing == true` или `platformListening`), он НЕ закрывается! В строке 1303 написано `if (state.phase != LocalVoicePhase.SPEAKING && state.phase != LocalVoicePhase.LISTENING)`, то есть если микрофон был в `LISTENING`, он остаётся в `LISTENING` и слушает динамик Марса!
   - Более того, когда в начале хода звучит `thinkingHint` («Секунду, думаю.») или когда между кусками потоковой речи (`SpeechChunker`) либо при переходе от `thinkingHint` к первому куску ответа случается короткое `onTtsSpeakingChanged(false)`, метод `onTtsSpeakingChanged(false)` в строке 1311 тут же вызывает `scheduleContinuousListening()`! Почему? Потому что `onAnswerCompleted` / `onAnswerEnded` сбрасывает `answerPending = false` в момент окончания *текстового* стрима LLM (когда модель быстро прислала короткий ответ), а TTS ещё только дочитывает первый кусок или `OfflineTtsSpeechEngine.watchSpeech` опрашивает `port.isReady()` каждые 100 мс.
   - В `OfflineTtsSpeechEngine.kt` / `LocalVoiceTts.kt`: когда `OfflineTtsBackend.speak()` вызывается, `port.isReady()` возвращает `true` всё время, пока `engine != null`! Посмотри внимательно на `OfflineTtsBackend.kt` строка 206:
     `override fun isReady(): Boolean = synchronized(lock) { !released && engine != null }`
     **ЭТО ГЛАВНЫЙ БАГ!** В `OfflineTtsSpeechEngine.kt` (строка 53) `watchSpeech` проверяет `if (port.isReady()) { finishSpeaking(); return }` через 100 мс после старта `speak()`! Так как `port.isReady()` в `OfflineTtsBackend` проверяет только `engine != null` (а НЕ `active == null`), `watchSpeech` **ровно через 100 мс после начала речи** считает, что речь закончилась, вызывает `finishSpeaking()` → `onEngineSilent()` → `onTtsSpeakingChanged(false)` → `scheduleContinuousListening()` → через 350 мс **открывает микрофон прямо во время того, как `OfflineTtsBackend` в своём потоке громко читает ответ Марса в динамик!** И открытый микрофон записывает голос самого Марса и отправляет его как новый вопрос пользователя!
2. **Что нужно сделать в Потоке 2:**
   - В `LocalVoiceTts.kt` и `OfflineTtsSpeechEngine.kt` (или через `OfflineVoiceSignal` / проверку активности): исправить определение окончания речи в `LocalVoiceTts` / `OfflineTtsSpeechEngine` и `LocalVoiceController`:
     - В `LocalVoiceTts`: пока `OfflineTtsBackend` реально произносит реплику, он публикует `OfflineVoiceSignal.publish(true)` при старте и `OfflineVoiceSignal.publish(false)` в `finish(utterance)`! Либо проверять реальное окончание озвучки, а не `port.isReady()`, который всегда `true`! (Если нужно поправить `OfflineTtsSpeechEngine.kt` — разрешено!).
   - В `LocalVoiceController.kt`:
     - Когда начинается озвучка (`onTtsSpeakingChanged(true)` или `speakNow` / `onAnswerStarted` / `onAnswerDelta`), **принудительно закрывать любой активный захват микрофона** (`if (platformListening) cancelPlatform...; if (capture?.isCapturing == true) closeCapture()`), чтобы микрофон физически не мог параллельно слушать динамик Марса.
     - Завести поле `private var lastSpeechEndedAtMs: Long = 0L` и увеличить `CONTINUOUS_RESTART_DELAY_MS` с `350L` до `650L` (хвост аудиобуфера AudioTrack + реверберация комнаты).
     - В `scheduleContinuousListening()`: запускать таймер автостарта микрофона ТОЛЬКО когда:
       1) `!answerPending` и `!streamedTurn`;
       2) `tts?.isSpeaking != true`;
       3) `state.phase == LocalVoicePhase.IDLE`.
     - В `continuousRestart`: перед вызовом `startListening()` ещё раз проверить `if (tts?.isSpeaking == true || answerPending) return`.
   - Проверить также `VoiceDialogLoop.kt` (серверный голосовой режим): убедиться, что на динамике (`AudioRoute.SPEAKER` / `AudioRoute.UNKNOWN`) без наушников эхо-реплика не может отправить сама себя в `onFinal`, если Марс сейчас говорит (`state == State.SPEAKING`).

### Поток 3 (Worker 3): Реалистичный Орб — настоящая планета из нашей космической 3D-заставки (`VoiceModeOrbView.kt`)
**Требование владельца:**
«сам орб отвратительно нарисован, игрушечный, не нужно пытаться его нарисовать, нужно вытащить именно реалистичную планету из нашей заставки и немного её под нас доработать», «Особое внимание уделить орбу, ещё раз повторю что его не надо самостоятельно рисовать, нужно взять планету из нашей космо 3д заставки».
**Что у нас в проекте:**
1. В проекте есть два источника заставки:
   - Фотореалистичный рендер Марса в `app-mobile/src/main/res/drawable-nodpi/mars_bg.webp` (1080×2400, который стоит в `splash_window_bg.xml` на заставке `SplashActivity` и в `mars_window_bg.xml`). В этом кадре шар Марса находится в координатах: центр `(500, 1203)`, диаметр ~846 px (y от 780 до 1626, x от 94 до 906).
   - И полноэкранный космический 3D-шейдер в `MarsSpaceBackgroundView.kt` (`RuntimeShader` AGSL на API 33+ с 3D-сферой, FBM-рельефом, терминатором и атмосферой).
   - А в `VoiceModeOrbView.kt` в Волне 18 вместо этого нарисовали мультяшный кружок из 12 плоских кружков-кратеров (`drawCraters` с `canvas.drawCircle`), овальной белой кляксы-шапки и мультяшного пунктирного колечка! Из-за этого он выглядит как дешёвая игрушка.
2. **Что обязан сделать Worker 3:**
   - Вырезать из `app-mobile/src/main/res/drawable-nodpi/mars_bg.webp` сам реалистичный шар планеты Марс с мягкой круглой альфа-маской по краю диска/атмосферы в отдельный ресурс `app-mobile/src/main/res/drawable-nodpi/mars_orb_planet.webp` (например 720×720 RGBA WebP lossless/high-quality через Python PIL, без чёрного прямоугольного фона, чтобы остался чистый реалистичный шар планеты из заставки с его настоящими каньонами, текстурой, терминатором и атмосферой!).
   - В `VoiceModeOrbView.kt`:
     - **Полностью убрать самодельные мультяшные художества** (`drawCraters`, `CRATER_LAT/LON/SIZE`, плоский овал полярной шапки `capPaint`, игрушечную орбиту с точками-хвостами `drawOrbit`).
     - Вместо них рендерить в центре орба **настоящую реалистичную планету из заставки** (`R.drawable.mars_orb_planet`, загруженную в `Bitmap` один раз с `BitmapShader` или круглой маской и мягким вращением/покачиванием + на API 33+ можно дополнить тонким сферическим шейдерным свечением атмосферы из `MarsSpaceBackgroundView`!).
     - Доработать её под голосовой орб Марса:
       1) Глубокое реалистичное атмосферное гало позади планеты, которое плавно меняет оттенок и интенсивность по фазе (`IDLE` — благородный тёмно-рубиновый Марс, `LISTENING` — живой алый/огненно-рубиновый отклик на голос пользователя, `THINKING` — тёплое янтарно-солнечное свечение короны, `SPEAKING` — яркая плазменно-голубая/неоновая корона и подсветка лимба в такт голосу Марса).
       2) Плавное физичное дыхание масштаба и яркости атмосферного лимба (серпа) от `smoothedLevel` (громкости речи) и `breath`, плюс мягкие концентрические акустические волны атмосферы во время речи/слушания.
       3) Сохранить публичный API `VoiceModeOrbView` (`render(tone: VoiceModeTone, pulses: Boolean)`, `setLevel(level: Float)`), отсутствие аллокаций в `onDraw` и все проверки `MarsPulsePolicy`.

### Поток 4 (Worker 4): Время сообщения и быстрые кнопки («Копировать», «Ответить», «Озвучить», «⋮») в карточках сообщений
**Корневые причины в коде сейчас:**
1. В `LocalChatTranscript.kt`:
   - В `LocalChatItem` (`LocalChatPresenter.kt`, строка 65) **вообще нет поля `createdAt: Long = 0L`**! Хотя в `LocalMessage` поле `createdAt: Long` уже есть и сохраняется в `LocalSessionStore`! При сборке `LocalChatItem` в `LocalChatPresenter.send()` (строки 588, 590) и `buildItems()` (строки 1075, 1084) `createdAt` просто выбрасывается!
   - В `LocalChatTranscript.userBubble(item)` (строки 179–206) рисуется просто голый `TextView` без шапки («Вы · 14:05») и без быстрых кнопок («Копировать», «Ответить»)!
   - В `LocalChatTranscript.AssistantCard` (строки 241–369) в шапке написано только слово `"Марс"`, нет ни пилюли модели, ни времени сообщения (`MessageTimePolicy.clockLabel(item.createdAt)`), ни ряда быстрых кнопок внизу карточки (`Копировать`, `Ответить`, `Озвучить`, `⋮` для меню действий)! Чтобы скопировать или ответить, человеку приходилось знать про скрытый long-press именно по заголовку `"Марс"`.
2. В `ChatMessageCardRenderer.kt` (серверный чат):
   - Внизу карточки (строки 227–273) есть кнопка `"Копировать"`, кнопка `moreBtn` (`⋮`) и `speakBtn`, но **нет быстрой кнопки `"Ответить"`** (цитирование в композер, которое сейчас спрятано внутри `⋮` → `MessageActionKind.QUOTE`), а если `message.timestamp == 0L` у только что созданного сообщения во время стрима, время не показывалось!
3. **Что обязан сделать Worker 4:**
   - В `LocalChatPresenter.kt`:
     - Добавить в `LocalChatItem` поле `val createdAt: Long = 0L`.
     - В `send()` передавать `createdAt = user.createdAt` для пользовательского `LocalChatItem` и `createdAt = now()` для ассистентского `LocalChatItem`.
     - В `buildItems(messages)` передавать `createdAt = message.createdAt` и для `LocalRole.USER`, и для `LocalRole.ASSISTANT`.
   - В `LocalChatTranscript.kt`:
     - Добавить в конструктор `LocalChatTranscript` колбэки (со значениями по умолчанию, чтобы не ломать существующие вызовы до склейки):
       `private val onReply: (LocalChatItem) -> Unit = {}`,
       `private val onSpeakText: (String) -> Unit = {}`.
     - В `AssistantCard`:
       - Сделать полноценную шапку карточки как в серверном чате: зелёная точка статуса + `"Марс"` слева, а справа — время сообщения `MessageTimePolicy.clockLabel(item.createdAt)` (`11sp`, `MarsTheme.TEXT_EM`).
       - Под телом сообщения (и футером токенов) добавить компактный ряд быстрых действий `actionsRow` (показывается, когда `!item.streaming && item.text.isNotBlank()`):
         1) Быстрая кнопка **«Копировать»** (`onCopyText(current.text)`);
         2) Быстрая кнопка **«Ответить»** (`onReply(current)`);
         3) Иконка/кнопка **«Озвучить»** (`R.drawable.ic_volume`, вызывает `onSpeakText(current.text)`);
         4) Кнопка **«⋮»** (`R.drawable.ic_more_vert`, вызывает `onMessageLongPress(current, current.id == lastAssistantId)`).
     - В `userBubble(item)`:
       - Оформить сообщение пользователя аккуратной карточкой/пузырём с шапкой (`"Вы"` + время `MessageTimePolicy.clockLabel(item.createdAt)` справа) и компактными быстрыми кнопками под текстом или в нижней строке пузыря: **«Копировать»**, **«Ответить»** и **«⋮»** (меню действий: Изменить, Копировать, Поделиться). При этом сохранить `userBubbles[item.id]` для инкрементального обновления текста!
   - В `ChatMessageCardRenderer.kt` и `ChatUiState.kt`:
     - Проверить, что в `ChatUiState.kt` при создании сообщений пользователя и ассистента (`ChatMessage("user", ...)` и `ChatMessage("assistant", ...)`) проставляется `timestamp = System.currentTimeMillis()`, если не передан явно — чтобы время отображалось сразу на всех новых сообщениях, а не только после перезагрузки истории с сервера!
     - В `ChatMessageCardRenderer.kt` в `actionsRow` рядом с кнопкой `"Копировать"` добавить быструю кнопку **`"Ответить"`** (вызывает новое действие `host.replyToMessage(host.messageAt(index) ?: message)` с дефолтной реализацией в `Host`: `fun replyToMessage(message: ChatMessage) {}`), чтобы и в серверном, и в локальном чате на каждой карточке были и время, и быстрые кнопки «Копировать» и «Ответить»!
