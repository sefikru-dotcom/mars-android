# Контракт Волны 20: Исправление активации разговора из списка диалогов, захвата первого слова, прямое подключение нейросетевой озвучки Edge TTS (GitHub `rany2/edge-tts`) и доводка фотореалистичного Орба с шейдером космической заставки

## Контекст и выявленные корневые причины

По итогам живой проверки Волны 19 на POCO F5 владельцем зафиксированы 4 задачи:

1. **«Голосовое общение работает только если его открыть внутри чата, если в списке диалогов открыть разговор, он не активируется»**
   - **Причина:** В `LocalSessionsActivity.kt` (строка 160) кнопка «Разговор» в шапке и `MarsQuickWidgetProvider` открывали `VoiceModeActivity.intentFor(this, transcript = true)`. Но `VoiceModeActivity` — это пустая витрина без движков (`callbacks == null`, см. KDoc `VoiceModeActivity`: *«Он не открывает микрофон, не создаёт распознаватель, не заводит синтез»*). Весь рабочий голосовой стек живёт внутри `LocalChatActivity` + `VoiceModeOverlay`.
   - **Решение:** В `LocalSessionsActivity` и `MarsQuickWidgetProvider` (действие `VOICE`) открывать `LocalChatActivity` с флагом `EXTRA_OPEN_VOICE_MODE = true` (для последнего активного диалога или нового диалога). В `LocalChatActivity.onCreate` / `onNewIntent` при `EXTRA_OPEN_VOICE_MODE == true` сразу после `setContentView` вызывать `openVoiceMode()`. Также если пользователь запустил `VoiceModeActivity` напрямую, она должна сразу перенаправлять в `LocalChatActivity(openVoiceMode = true)` и закрываться (`finish()`).

2. **«При открытии разговора внутри чата, часть слов он пропускает, в основном первое слово»**
   - **Причина:**
     1) В `VoiceModeOverlay.show()` вызывается `channel.warmUpAsrInBackground()` и тут же `channel.startListening()`. Если `stt.isReady == false` (модель ещё не прогрета), `LocalVoiceController.startListening()` в строке 345 видит `platformFallbackAllowed(wrapper) == true` и запускает системный `SpeechRecognizer` (`startPlatformSpeechRecognizer()`), который тратит 600–900 мс на IPC-биндинг к Google Speech Service и `onReadyForSpeech`, съедая первое слово.
     2) Даже когда `SherpaAsrBackend` уже готов (`wrapper.isReady == true`), при каждом `startCapture(wrapper)` создаётся новый `AudioRecord` (`AndroidAudioSourceFactory.open`), у которого аппаратный тракт микрофона + `AcousticEchoCanceler` + AGC на Android/Xiaomi прогревается первые ~250–350 мс, плюс `wrapper.startUtterance()` вызывается ПОСЛЕ `record.startRecording()`, а первые фреймы на границе тишины и речи без предбуфера теряют начальную согласную/первое слово.
     3) Кроме того, в `SherpaAsrBackend.kt` `rule1` (minTrailingSilence = 2.4s), `rule2` (1.2s) сбрасывают стрим при паузе в начале, а `HandsFreeCapture` не держит кольцевой предбуфер (pre-roll) и не подаёт 200 мс тишины (lead-in silence pad) перед первой речью в `OnlineStream`, из-за чего Zipformer2 transducer съедает первую фонему на холодном окне фич (`FeatureConfig`).
   - **Решение:**
     1) Прогревать `StreamingAsrEngine` сразу при старте `LocalChatActivity.onCreate` и `LocalSessionsActivity.onCreate` (единый кэшированный инстанс или ранний прогрев), чтобы к моменту нажатия «Разговор» `wrapper.isReady` уже был `true`.
     2) В `LocalVoiceController.startListening()`: если `asrModelInstalled` на диске (`ModelDownloader.forApp(context).isAvailable()`), НЕ уходить на медленный `startPlatformSpeechRecognizer()`, который глотает первое слово на биндинге, а дождаться загрузки офлайн-движка (с сохранением кольцевого предбуфера микрофона!).
     3) **Главное (Pre-roll буфер + Lead-in silence pad):**
        - В `StreamingAsrEngine.beginUtteranceLocked()` сразу после `b.newStream(...)` скармливать в стрим **160 мс тишины** (`FloatArray(2560)` нулей, 16 кГц) и вызывать `drainLocked(b)`! Для streaming Zipformer2 это прогревает контекстное окно мел-спектрограммы и декодера, чтобы первая же фонема пользователя ложилась на прогретый контекст, а не попадала в мёртвую зону первого окна.
        - В `LocalVoiceController.startCapture(wrapper)`: сначала подготовить `wrapper.startUtterance()` (который уже прогрет 160 мс нулями), и только затем запускать `HandsFreeCapture.start(...)`, а если захват ждёт дозагрузки движка (`pendingStart = true`), `HandsFreeCapture` открывать СРАЗУ параллельно с загрузкой модели и складывать PCM-чанки в кольцевой буфер предзаписи (`PreRollAudioBuffer`, до 2.5 секунд = 80 КБ), чтобы когда пользователь начал говорить в первые миллисекунды открытия орба, его первое слово было записано и мгновенно скормлено в `wrapper.acceptPcm`!

3. **«В настройках теперь выбор из 4 голосов, как в вэбе, дмитрий, светлана - один и тот же голос, отличается только ирина, ирина звучит максимально убого, остальные 3 тупит на знаках препинания, отступах и прочем, долгие паузы и прерывания, долго анализирует сообщение из чата, большая задержка перед началом, найди качественную хорошую озвучку на гитхабе и интегрируй»**
   - **Причина (подтверждена логами и кодом):**
     1) В `LocalChatActivity` все 3 сетевых голоса («Как в вебе», «Светлана», «Дмитрий») шли через `RepositoryServerTtsClient` на сервер `/api/tts` с `credentialStore.read()`. Когда токен отсутствует/просрочен или сервер возвращает `429 rate limit exceeded` (лимитер 1 запрос в 2 секунды на `/api/tts`, зафиксировано в `journalctl`: `status: 429`), `ServerTtsSpeechEngine` молча падал в `fallback = AndroidSpeechEngine` (системный Google TTS)! Поэтому и «Как в вебе», и «Светлана», и «Дмитрий» звучали ОДНИМ И ТЕМ ЖЕ системным голосом Android TTS!
     2) Даже когда `/api/tts` отвечал `200`, `LocalVoiceTts` слал текст кусками через `ServerTtsSpeechEngine.speak()`, который НЕ нормализовал числа/знаки через `VoiceNormalizer`, а каждый второй кусок отбивался сервером с `429` (так как между первым клаузом и вторым проходило < 2 секунд!) и проваливался на системный TTS с огромной паузой.
     3) Локальная модель Ирины (`OfflineTtsBackend`) на телефоне использовала старую `vits-piper-ru_RU-irina-medium-int8` в `files/tts/`, плюс `SILENCE_SCALE = 0.2f` и `NOISE_SCALE = 0.667f`, без `VoiceNormalizer`, из-за чего она тараторила с металлическим звоном и спотыкалась на markdown/пунктуации.
   - **Решение (Интеграция прямого нейросетевого синтеза `EdgeNeuralTtsClient` по протоколу GitHub `rany2/edge-tts` v7.2 + нормализация текста и стриминг без лимитера):**
     1) Реализовать в `core-network` (или `app-mobile`) прямой клиент **Microsoft Edge ReadAloud Neural TTS** по алгоритму из открытого репозитория GitHub `rany2/edge-tts` (`wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1` с генерацией DRM-токена `Sec-MS-GEC` v2 `CS64-SHA256` и заголовками `Origin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold`, `Sec-MS-GEC-Version: 2-143.0.3650.75`, `Cookie: muid=...` на встроенном `OkHttpClient.newWebSocket`):
        - Работает **напрямую с телефона без сервера Марса**, без `device-credential` и **без 2-секундного серверного rate-limit (429)**!
        - Задержка до первого аудио (TTFB) — **~350–450 мс** вместо 2.5–7 секунд через двойной буфер сервера.
        - Если прямой WebSocket к `speech.platform.bing.com` недоступен (например, блочится провайдером), прозрачно фолбэчится на серверный `/api/tts` (с автоматическим ожиданием/ретраем при 429).
     2) Настоящие живые нейросетевые голоса в списке выбора (`ServerTtsVoices` + `VoiceProsodyPolicy`):
        - `natural-svetlana` — **Светлана (Нейро)** (`ru-RU-SvetlanaNeural`, женский живой голос);
        - `natural-dmitry` — **Дмитрий (Нейро)** (`ru-RU-DmitryNeural`, мужской живой голос);
        - `natural-dariya` — **Дарья / Многоязычный (Нейро)** (`en-US-AvaMultilingualNeural` / `fr-FR-VivienneMultilingualNeural` — проверим и подключим лучший 3-й нейро-голос с чистым русским произношением, либо `ru-RU-DariyaNeural`);
        - `local-irina` — **Ирина (Офлайн)** (работает без интернета, с мягкими параметрами `noiseScale = 0.55f`, `noiseScaleW = 0.70f`, `silenceScale = 0.35f` и обязательным прогоном через `VoiceNormalizer`).
        - Никакого дубликата «Как в вебе» = «Светлана»: когда `voiceId == null`, в шторке сразу подсвечивается `natural-svetlana` как дефолтный голос (или 4 реально разных голоса без фейковой кнопки-дубля!).
     3) **Устранение затупов на знаках препинания, отступах и долгого анализа сообщения:**
        - Перед отправкой любого текста в `LocalVoiceTts.speak` / `appendStream` / `VoiceOutputController`: прогонять через улучшенный `SpeechTextCleaner` (`SpeechContentFilter.stripForSpeech` + `VoiceNormalizer.normalize` + очистка лишних переносов строк, тире, скобок, кавычек, списков `1.`, `-`, `*`, чтобы синтезатор НИКОГДА не спотыкался на знаках препинания и отступах).
        - В `LocalVoiceTts`: при нажатии «Озвучить» на длинном сообщении в чате (`speak(text)`) НЕ отправлять все 3000 символов одним гигантским запросом (который синтезируется 10–20 секунд перед первым звуком!), а разбивать через `SpeechChunker` на предложения: первое короткое предложение (до 120 символов) уходит в синтез МГНОВЕННО (звук начинается через ~0.4 с!), а пока оно играет, `LocalVoiceTts` в фоне предсинтезирует (`prefetch`) следующий кусок (`nextPieceJob: Deferred<ServerTtsResult>`), так что между предложениями **0 мс паузы**!

4. **«Сам орб планета наконец то получилась но она не ровно расположена и нужно её чуть больше сделать, неоновый фон видно что самостоятельно нарисован и выглядит наляписто, нужно взять элементы из 3д заставки и слепить из них неоновое свечение в такт голоса»**
   - **Причина (доказана попиксельным анализом):**
     1) **Почему планета неровно расположена:** В `mars_bg.webp` (и вырезанном `mars_orb_planet.webp`) Марс освещён слева-сверху (серп и светлая часть занимают `x = 24..556`, центр тяжести яркости находится в `x = 290, y = 386` при геометрическом центре `384, 384`), а правая нижняя треть диска — почти чёрная тень (`RGB ≈ 4, 2, 2`), которая сливается с тёмным фоном! Кроме того, в `VoiceModeOrbView.kt` корона `haloOuterPaint` была искусственно сдвинута влево-вверх (`HALO_SHIFT_X = 0.14f`, `HALO_SHIFT_Y = 0.18f`), а в `VoiceModeOverlay.kt` колонка имела `Gravity.BOTTOM` с `topMargin = dp(24)`, из-за чего шар висел не по центру экрана и визуально «заваливался» влево-вверх!
     2) **Почему планета мелкая:** В `VoiceModeOverlay.kt` размер `ORB_DP = 232`, а внутри `VoiceModeOrbView.kt` `PLANET_RATIO = 0.49f` — то есть сам шар планеты был всего `232 * 0.49 = 113 dp` (~30% ширины экрана), тогда как на заставке (`mars_bg.webp` и `MarsSpaceShaderPolicy`) диаметр Марса составляет **79% ширины экрана** (`PLANET_RADIUS = 0.42f * minDim`, диаметр `84%`)!
     3) **Почему неоновый фон выглядел наляписто и самодельным:** В `VoiceModeOrbView.kt` поверх `Canvas` рисовались 4 плоских круга `RadialGradient` (`nebulaRubyPaint`, `nebulaAmberPaint`, `nebulaPlumPaint`, `neonVeilPaint`), зажатые внутри квадрата `232dp`, а у самого `VoiceModeOverlay` фон был глухой серый `MarsTheme.GLASS_PANEL`! Между тем в проекте УЖЕ ЕСТЬ настоящий 3D/GPU-шейдер космической заставки — `MarsSpaceBackgroundView` + `MarsSpaceShaderPolicy` (с настоящей FBM-туманностью `fbm()`, мерцающими звёздами, кометами и рэлеевским свечением атмосферы)!
   - **Решение:**
     1) В `VoiceModeOverlay.kt` (и `VoiceModeActivity.kt`) положить в корень экрана настоящий живой 3D-космос заставки `MarsSpaceBackgroundView` (или его режим для голосового экрана), а сам `MarsSpaceShaderPolicy` / `MarsSpaceBackgroundView` научить принимать `uVoiceEnergy` (уровень голоса `0..1`) и `uVoiceTint` (неоновый вектор цвета фазы), чтобы **сама 3D-заставка (туманность FBM, газовые облака и атмосферная корона вокруг планеты)** плавно вспыхивала и переливалась неоновым свечением в такт голосу на весь экран!
     2) В `VoiceModeOrbView.kt`:
        - Убрать наляпистые самодельные круги `nebulaRubyPaint`, `nebulaAmberPaint`, `nebulaPlumPaint`, `voidPaint`, которые рисовались внутри маленького квадрата вьюхи.
        - Сделать планету **крупнее и строго по центру**: увеличить `ORB_DP` в `VoiceModeOverlay` до `300 dp` (а `PLANET_RATIO` в `VoiceModeOrbView` поднять с `0.49f` до `0.66f`, чтобы диаметр самой планеты вырос со `113 dp` до `~200 dp` — в 1.75 раза крупнее!).
        - Выровнять оптический и геометрический центр планеты: убрать асимметричный сдвиг `HALO_SHIFT_X / HALO_SHIFT_Y` (`0f`), отцентрировать текстуру `mars_orb_planet.webp` и подсветить правый-нижний (ночной) лимб мягким неоновым контровым светом (rim light из формулы `pow(1.0 - mu, 3.2)` шейдера `MarsSpaceShaderPolicy`), чтобы весь шар Марса читался идеально круглой, симметрично стоящей по центру 3D-сферой!
        - В `VoiceModeOverlay.kt` выровнять компоновку: орб располагается в центре верхней/средней части экрана (`Gravity.CENTER`), а блок текста и кнопок — внизу, чтобы планета стояла ровно по оси экрана.

---

## Распределение по воркерам (4 изолированных потока)

**КРИТИЧЕСКОЕ ПРАВИЛО ДЛЯ ВСЕХ ВОРКЕРОВ:**
НЕ запускать `gradle`, `gradlew`, `build-mars.sh`, `kotlinc`, `javac`, `aapt2`, `d8`, `r8`! Сборку и тесты прогоняет ТОЛЬКО координатор после завершения всех 4 воркеров.

### Поток 1 (Worker 1): Прямой нейросетевой синтез Edge Neural TTS (`rany2/edge-tts` WebSocket + DRM v2) и 4 живых голоса
**Файлы во владении:**
- `core-network/src/main/java/ru/mars/network/repository/EdgeNeuralTtsClient.kt` (НОВЫЙ)
- `core-network/src/main/java/ru/mars/network/repository/ServerTtsVoiceProfile.kt`
- `app-mobile/src/main/java/ru/mars/mobile/VoiceProsodyPolicy.kt`
- `app-mobile/src/main/java/ru/mars/mobile/ServerTtsSpeechEngine.kt`
- `core-network/src/test/java/ru/mars/network/repository/EdgeNeuralTtsClientTest.kt` (НОВЫЙ)
- `app-mobile/src/test/java/ru/mars/mobile/VoiceProsodyPolicyTest.kt`

### Поток 2 (Worker 2): Мгновенный старт озвучки длинных сообщений (предсинтез + нарезка), очистка знаков препинания и улучшение офлайн-голоса
**Файлы во владении:**
- `app-mobile/src/main/java/ru/mars/mobile/domain/util/SpeechContentFilter.kt`
- `app-mobile/src/main/java/ru/mars/mobile/VoiceNormalizer.kt`
- `app-mobile/src/main/java/ru/mars/mobile/LocalVoiceTts.kt`
- `app-mobile/src/main/java/ru/mars/mobile/tts/OfflineTtsManifest.kt`
- `app-mobile/src/main/java/ru/mars/mobile/tts/OfflineTtsBackend.kt`
- `app-mobile/src/test/java/ru/mars/mobile/SpeechContentFilterTest.kt`

### Поток 3 (Worker 3): Активация «Разговора» из списка диалогов/виджета и устранение проглатывания первого слова (Pre-roll + Lead-in silence)
**Файлы во владении:**
- `app-mobile/src/main/java/ru/mars/mobile/LocalSessionsActivity.kt`
- `app-mobile/src/main/java/ru/mars/mobile/VoiceModeActivity.kt`
- `app-mobile/src/main/java/ru/mars/mobile/widget/MarsQuickWidgetProvider.kt`
- `app-mobile/src/main/java/ru/mars/mobile/StreamingAsrEngine.kt`
- `app-mobile/src/main/java/ru/mars/mobile/PreRollAudioBuffer.kt` (НОВЫЙ)
- `app-mobile/src/main/java/ru/mars/mobile/LocalVoiceController.kt`
- `app-mobile/src/test/java/ru/mars/mobile/PreRollAudioBufferTest.kt` (НОВЫЙ)

### Поток 4 (Worker 4): Центровка и укрупнение планеты Орба + настоящее неоновое свечение из 3D-шейдера заставки в такт голоса
**Файлы во владении:**
- `app-mobile/src/main/java/ru/mars/mobile/MarsSpaceShaderPolicy.kt`
- `app-mobile/src/main/java/ru/mars/mobile/MarsSpaceBackgroundView.kt`
- `app-mobile/src/main/java/ru/mars/mobile/VoiceModeOrbView.kt`
- `app-mobile/src/main/java/ru/mars/mobile/VoiceModeOverlay.kt`
- `app-mobile/src/test/java/ru/mars/mobile/VoiceModeOrbViewContractTest.kt`
- `app-mobile/src/test/java/ru/mars/mobile/VoiceModeOverlayContractTest.kt`

### Финальная склейка (Координатор):
- `app-mobile/src/main/java/ru/mars/mobile/LocalChatActivity.kt` (поддержка `EXTRA_OPEN_VOICE_MODE`, ранний прогрев ASR и подключение `EdgeNeuralTtsClient`).
- Запуск всех unit-тестов, сборка релизного APK, проверка R8 и установка на POCO F5 (`6d6fe9f6`).
