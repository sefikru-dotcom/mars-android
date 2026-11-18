# Контракт Волны 21: Мягкие переходы Орба, устранение 5–10с паузы на первой запятой (предсинтез + полные предложения), голосовые модели и многофункциональный виджет рабочего стола

## 1. Обзор и корневые причины 4 пунктов владельца

### Пункт 1: «Орб слишком сильно контрастит, не должно быть слишком ярких переходов как сейчас с красного на голубой, переходы от состояния должны быть плавными, мягкими»
**Корневые причины в текущем коде:**
1. В `VoiceNeonPalette` (`VoiceModeOrbView.kt`) `SPEAKING` был резким кислотно-голубым `0xFF4CC8FF`, который контрастировал на 180° с красным Марсом (`0xFFC41E3A` / `0xFFFF3D4F`).
2. В `VoiceModeOrbView.render(tone, pulses)` при смене тона вызывался `applyTone()` → мгновенное присваивание `neon = neonFor(tone)`, `haloEnergy = energy[0]` и мгновенный `buildShaders()` за **0 мс** (в один кадр!).
3. В `MarsSpaceBackgroundView.setVoiceState(level, neonColor)` цвет `uVoiceNeon` тоже переключался скачком в один кадр без интерполяции во времени (`dt`).
4. В `VoiceModeOverlay.render(state)` подпись `phaseLabel` красилась резкими системными цветами статусов (`STATUS_ONLINE` зелёный, `STATUS_WARNING` жёлтый, `STATUS_INFO` синий).

**Решение (Поток 1 — Worker 1):**
1. **Тёплая гармоничная палитра `VoiceNeonPalette` без резкого синего/голубого контраста:**
   - `IDLE`: `0xFFB8293D.toInt()` — глубокий благородный тёмно-рубиновый;
   - `LISTENING`: `0xFFE84A58.toInt()` — мягкий тёплый кораллово-рубиновый (близкий по тону, без кислотного алого);
   - `THINKING`: `0xFFE58B42.toInt()` — мягкий янтарно-терракотовый закат Марса;
   - `SPEAKING`: `0xFFF2A679.toInt()` — тёплый персиково-золотистый солнечный свет атмосферы Марса (больше никакого резкого перехода с красного на голубой; при этом `blue(SPEAKING) = 0x79 = 121 > blue(IDLE) = 0x3D = 61`, но сам оттенок тёплый, гармоничный с планетой).
2. **Плавная экспоненциальная интерполяция цвета и энергии слоёв в `VoiceModeOrbView` и `MarsSpaceBackgroundView`:**
   - Добавить в `VoiceNeonPalette` чистые функции:
     ```kotlin
     fun lerpColor(from: Int, to: Int, t: Float): Int
     fun stepFactor(dtSec: Float, tauSec: Float = TRANSITION_TAU_SEC): Float
     ```
     где `TRANSITION_TAU_SEC = 0.45f` (плавный мягкий переход за ~1.1–1.3 секунды без рывка).
   - В `VoiceModeOrbView`: хранить целевые значения (`targetNeon`, `targetHaloEnergy`, `targetCoronaEnergy`, `targetLimbEnergy`, `targetWaveEnergy`) и текущие плавно меняющиеся (`neon`, `haloEnergy`, `coronaEnergy`, `limbEnergy`, `waveEnergy`). В `doFrame` (когда `breathing == true`) на каждом кадре приближать текущие энергии и `neon` к целевым через `VoiceNeonPalette.stepFactor(dtSec)` и вызывать `buildShaders()`, пока цвет/энергия не сошлись с целью. Если анимации отключены (`!motionAllowed`), применять сразу.
   - В `MarsSpaceBackgroundView`: хранить целевой цвет `(targetNeonR, targetNeonG, targetNeonB)` и текущий `(voiceNeonR, voiceNeonG, voiceNeonB)`. На каждом кадре в `onFrame(frameTimeNanos)` плавно интерполировать `voiceNeonR/G/B` к `targetNeonR/G/B` с тем же `stepFactor(dt)` и передавать в `renderer` и `canvasBackdrop`, чтобы 3D-туманность шейдера тоже перетекала из состояния в состояние мягко и плавно, без вспышки!
   - В `VoiceModeOverlay.toneColor(tone)`: убрать зелёный/синий разнобой в `phaseLabel`, использовать мягкие приглушённые оттенки (`VoiceNeonPalette.lerpColor(VoiceNeonPalette.of(tone), MarsTheme.TEXT, 0.55f)` для активных фаз и `MarsTheme.TEXT_MUTED` для `IDLE`).

---

### Пункт 2: «Языковые модели полный отстой, начинает говорить, доходит до запятой и останавливается на 5-10 секунд»
**Корневые причины в текущем коде (доказаны трассировкой `SpeechChunker` + `LocalVoiceTts` + `ServerTtsSpeechEngine`):**
1. **`SpeechChunker.firstCut` резал на ПЕРВОЙ ЗАПЯТОЙ (даже после 1–2 слов!)**, а второй кусок (`batchCut`) ждал накопления `BATCH_CHARS = 220` символов!
   - Пример: модель отвечает `"Конечно, сейчас расскажу подробно..."`.
   - Первым куском уходит `"Конечно,"` (1 слово, длительность аудио **0.5 секунды**!).
   - Второй кусок НЕ отдаётся, пока LLM не сгенерирует ещё 220 символов, и ТОЛЬКО ПОСЛЕ того как `"Конечно,"` доиграло (`onEngineSilent()`), начинается сетевой синтез второго куска на 220 символов!
2. **В `LocalVoiceTts` и `ServerTtsSpeechEngine` НЕ БЫЛО предсинтеза (prefetch / lookahead) очереди!**
   - `LocalVoiceTts` держал очередь `pending: ArrayDeque<Piece>`, но отправлял кусок N+1 в `output.speakNow()` ТОЛЬКО в `onEngineSilent()` — то есть когда кусок N уже полностью отзвучал и наступила тишина!
   - В это время кусок N+1 шёл в `RepositoryServerTtsClient` (или `OfflineTtsBackend`) и синтезировался с нуля (сетевой запрос к Edge/серверу или генерация ONNX), а если прямой сокет таймаутил (8 с!) или сервер ловил `429` (ожидание `2000 мс`!), пользователь сидел в полной тишине **5–10 секунд** ровно после первой запятой!

**Решение (Поток 2 — Worker 2):**
1. **`SpeechChunker.kt` — НЕ резать на первой запятой и не ждать 220 символов для второй фразы:**
   - Убрать запятую `,` и тире из раннего отсечения первого куска! Первый кусок должен завершаться на **конце предложения** (`.`, `!`, `?`, `…`, `\n`, `;`, `:`), либо — если предложение длинное — на запятой/пробеле, но **не раньше `FIRST_CLAUSE_MIN_CHARS = 48` символов** (и не длиннее `FIRST_MAX_CHARS = 110` символов)! Тогда Марс не произносит одно слово `"Конечно,"` с обрывом, а говорит осмысленную первую фразу целиком (3–4 секунды речи, пока в фоне уже синтезируется продолжение!).
   - В `batchCut` (последующие куски): снизить `REST_MAX_CHARS` (`BATCH_CHARS`) с `220` до `150`, и главное — если в буфере уже есть **законченное предложение** (`lastSentenceEnd`) длиной `>= MIN_SENTENCE_BATCH_CHARS (45 символов)`, отдавать его СРАЗУ, не дожидаясь 150 символов! Тогда фразы идут в очередь синтеза плавно предложение за предложением.
2. **Конвейерный предсинтез (Prefetch / Lookahead) в `ServerTtsSpeechEngine` и `LocalVoiceTts`:**
   - В `ServerTtsSpeechEngine`: добавить метод
     ```kotlin
     fun prefetch(text: String)
     ```
     (и метод `fun prefetch(text: String) = Unit` в `VoiceOutputController.SpeechEngine`, `PreferOfflineEngine`, `VoiceOutputController`), который запускает `client.synthesize(text)` в фоне (`scope.async`) и кладёт результат в кэш предсинтеза (`prefetchCache: ConcurrentHashMap<String, Deferred<ServerTtsResult>>` или слот), пока играет текущая фраза! Когда `speak(text)` вызывается для уже предсинтезированного куска, `ServerTtsSpeechEngine` мгновенно забирает готовый `ServerTtsResult.Audio` из `prefetchCache` (0 мс ожидания сети!) и сразу передаёт в `playback.play(...)`!
   - В `LocalVoiceTts`: как только кусок N запустился (`speakPieceNow` вернул `SPOKEN`) или когда в `pending` добавляется новый кусок во время `engineBusy == true`, немедленно вызывать `output.prefetch(pending.first().text)` для следующего куска в очереди!
   - В `EdgeNeuralTtsClient`: снизить `CONNECT_TIMEOUT_SECONDS` с `8L` до `3L` и `READ_TIMEOUT_SECONDS` с `15L` до `6L`, чтобы при недоступности прямого сокета переключение на резерв происходило быстро, а не держало паузу 8–15 секунд.

---

### Пункт 3: «Давай рассмотрим платные голосовые модели, у плюсвайба есть такие? какие ещё есть варианты?» + Подключение облачного OpenAI-совместимого TTS (`/v1/audio/speech`)
**Факты, проверенные прямыми запросами к API PlusVibe (`https://plusvibeapi.ru/v1`):**
1. В каталоге `/v1/models` у PlusVibe 97 моделей (текстовые LLM + эмбеддинги + `qwen3.8-omni-flash` и `minimax-m3.1-flash`, которые по `/v1/chat/completions` принимают только текст, а аудио-модальность не отдают).
2. На эндпоинте `POST https://plusvibeapi.ru/v1/audio/speech` у PlusVibe объявлена модель **`gpt-4o-mini-tts`** (остальные `tts-1`, `tts-1-hd`, `speech-02-hd`, `eleven_multilingual_v2` возвращают `400 The requested speech model is unavailable`). При этом `gpt-4o-mini-tts` на PlusVibe сейчас отвечает крайне нестабильно: 1 из 10 запросов отдал валидный MP3 (`16 128 байт, audio/mpeg` на голос `alloy`), а остальные 9 из 10 зависли по таймауту чтения (`>15 с`). То есть апстрим `gpt-4o-mini-tts` у PlusVibe сейчас перегружен/отваливается.
3. **Что делаем в коде (Поток 3 — Worker 3) + что даём в отчёте владельцу:**
   - Даём полный технический расклад по платным голосовым моделям (PlusVibe, ElevenLabs Flash v2.5 / Multilingual v2, OpenAI `gpt-4o-mini-tts` / Realtime API, Yandex SpeechKit v3, Cartesia Sonic-2, MiniMax Speech-02-HD).
   - В `core-network` создаём универсальный клиент платного облачного синтеза **`OpenAiSpeechTtsClient`** (`POST /v1/audio/speech` с поддержкой `model = "gpt-4o-mini-tts"`, `voice`, `speed`, `instructions`, жёстким таймаутом `4.5 с`, чтобы при зависании апстрима PlusVibe мгновенно уходить на быстрый нейро-голос без паузы) и добавляем профиль **`PLUSVIBE_ONYX`** (`id = "plusvibe-gpt4o-mini-tts"`, `engine = "openai-tts"`, `voice = "onyx"`, подпись в шторке: **«Оникс (PlusVibe / OpenAI TTS)»** — платный облачный голос `gpt-4o-mini-tts` через API-ключ провайдера, с автоматическим фолбэком на Edge Neural / сервер при таймауте PlusVibe!).

---

### Пункт 4: «Виджет андроид марса это вообще что такое? что за убожество? при нажатии на него он просто открывает приложение, нужно разработать многофункциональный удобный и полезный виджет но чтобы смотрелся он приятно и без лишнего цирка»
**Корневые причины в текущем коде (доказаны чтением `SplashActivity`, `MainActivity`, `ChatActivity`, `LocalChatActivity`, `MarsQuickWidgetProvider`):**
1. **Почему нажатие на любую кнопку виджета просто открывало приложение без действия:**
   - Когда процесс убит или стек пуст, а также в ряде сценариев лаунчера, либо когда человек пользуется **Локальным чатом** (`LocalChatActivity`):
     - `LocalChatActivity` ВООБЩЕ НЕ обновлял виджет (`MarsQuickWidgetProvider.publishState` вызывался только из серверного `ChatActivity`!) — поэтому на виджете всегда висела мёртвая заглушка *«Готов к работе / Нажмите, чтобы продолжить диалог или задать вопрос»*!
     - Если серверный `store.read() == null` (пользователь работает через локальный чат с прямым провайдером без серверного логина!), `ChatActivity` при открытии из виджета редиректил на `MainActivity` (экран пароля), теряя все действия!
     - В `SplashActivity.hasPushTarget()` и `MainActivity.openChat()` проверялся только `pushSessionId`, но НЕ проверялся `MarsWidgetActionsPolicy.EXTRA_WIDGET_ACTION`!
2. **Почему виджет выглядел убого и был малополезным:**
   - В `widget_mars_quick.xml` не было ни переключателя/индикатора режима (Сервер / Локально), ни текущей модели, ни быстрых пресетов/инструментов (Задачи, Память, Разговор, Новый чат, Диктовка), ни кнопки быстрого обновления/статуса, а карточка показывала только две скупые строчки текста и 4 одинаковых кружка внизу.

**Решение (Поток 4 — Worker 4):**
1. **Сделать виджет `MarsQuickWidgetProvider` (4×2 / 4×3) по-настоящему многофункциональным, стильным (строгий обсидиановый glass-дизайн без цирка) и полезным как для Серверного, так и для Локального чата:**
   - **Верхняя строка (Статус-бар виджета):**
     - Иконка планеты + бренд `МАРС` + бейдж активной модели/режима (`widget_mars_model_badge`, например `Claude Haiku 5.5` или `Локально · Gemini`, тап открывает выбор/настройки или список диалогов) + метка времени `widget_mars_updated` + кнопка `Диалоги` (`widget_mars_sessions`).
   - **Карточка живого контекста (`widget_mars_dialog`):**
     - Заголовок активного диалога (`widget_mars_session_title`) и 2 строки последнего ответа (`widget_mars_snippet`). Обновляется **И из `ChatActivity`, И из `LocalChatActivity`**! Запоминает `isLocal: Boolean` в `MarsWidgetState`, чтобы тап по диалогу открывал именно тот чат (серверный `ChatActivity` или локальный `LocalChatActivity`), в котором шёл последний разговор!
   - **Поле быстрого ввода и голоса (Композер-бар):**
     - Капсула `Спросить Марс…` (`widget_mars_input`) — мгновенно открывает активный чат с поднятой клавиатурой;
     - Кнопка `Диктовка` (`widget_mars_mic`) — мгновенно включает запись голоса;
     - Кнопка `Разговор` (`widget_mars_voice_mode`) — мгновенно открывает полноэкранный голосовой Орб (`LocalChatActivity` + `EXTRA_OPEN_VOICE_MODE`);
     - Кнопка `Новый чат` (`widget_mars_star`) — мгновенно начинает чистый диалог с фокусом ввода.
   - **Нижняя панель быстрых разделов в 1 тап (без лишнего цирка — строгие стеклянные пилюли):**
     - `widget_mars_chip_local` («Локально» → `LocalChatActivity` / `LocalSessionsActivity`);
     - `widget_mars_chip_tasks` («Задачи» → `TodosActivity`);
     - `widget_mars_chip_memory` («Память» → `LocalMemoryActivity` / `MemoryActivity`);
     - `widget_mars_chip_music` («Музыка» → `MusicActivity`).
   - **Железная маршрутизация тапов:**
     - Если в `MarsWidgetState` последний активный чат — локальный (`isLocal == true`) или серверного `credential` нет, действия `COMPOSE`, `VOICE`, `NEW_CHAT` из виджета маршрутизируются прямо в `LocalChatActivity` (где `applyWidgetAction` поднимает клавиатуру, включает `voice.controller.startListening()` или начинает `startNewLocalChat()`)!
     - Если `SplashActivity` или `MainActivity` получают интент с `EXTRA_WIDGET_ACTION`, они пробрасывают его напрямую в целевой чат без потери extra.

---

## 2. Таблица разделения файлов между воркерами

| Поток | Владелец | Разрешённые файлы | Запрещено трогать |
|---|---|---|---|
| **Worker 1 (Орб: мягкая палитра и плавная интерполяция)** | Subagent 1 | `app-mobile/src/main/java/ru/mars/mobile/VoiceModeOrbView.kt`, `app-mobile/src/main/java/ru/mars/mobile/MarsSpaceBackgroundView.kt`, `app-mobile/src/main/java/ru/mars/mobile/VoiceModeOverlay.kt`, `app-mobile/src/test/java/ru/mars/mobile/VoiceModeOrbViewContractTest.kt`, `app-mobile/src/test/java/ru/mars/mobile/VoiceModeOverlayContractTest.kt` | Любые другие файлы, Gradle/kotlinc |
| **Worker 2 (Речь: устранение паузы 5–10с на запятой + предсинтез)** | Subagent 2 | `app-mobile/src/main/java/ru/mars/mobile/voice/SpeechChunker.kt`, `app-mobile/src/main/java/ru/mars/mobile/LocalVoiceTts.kt`, `app-mobile/src/main/java/ru/mars/mobile/VoiceOutputController.kt`, `app-mobile/src/main/java/ru/mars/mobile/PreferOfflineEngine.kt`, `app-mobile/src/test/java/ru/mars/mobile/voice/SpeechChunkerTest.kt` | `ServerTtsSpeechEngine.kt` (ведёт Worker 3), `LocalChatActivity.kt`, Gradle/kotlinc |
| **Worker 3 (Платный OpenAI/PlusVibe TTS + предсинтез в ServerTtsSpeechEngine)** | Subagent 3 | `core-network/src/main/java/ru/mars/network/repository/OpenAiSpeechTtsClient.kt` (НОВЫЙ), `core-network/src/main/java/ru/mars/network/repository/EdgeNeuralTtsClient.kt`, `core-network/src/main/java/ru/mars/network/repository/ServerTtsVoiceProfile.kt`, `app-mobile/src/main/java/ru/mars/mobile/ServerTtsSpeechEngine.kt`, `app-mobile/src/main/java/ru/mars/mobile/VoiceProsodyPolicy.kt`, `core-network/src/test/java/ru/mars/network/ServerTtsVoiceContractTest.kt`, `app-mobile/src/test/java/ru/mars/mobile/VoiceProsodyPolicyTest.kt`, `app-mobile/src/test/java/ru/mars/mobile/ServerTtsSpeechEngineTest.kt`, `app-mobile/src/test/java/ru/mars/mobile/SpeechVoiceProfileSelectorTest.kt` | `LocalVoiceTts.kt`, `LocalChatActivity.kt`, Gradle/kotlinc |
| **Worker 4 (Многофункциональный виджет 4×2 и компактный 4×1)** | Subagent 4 | `app-mobile/src/main/java/ru/mars/mobile/widget/MarsWidgetActionsPolicy.kt`, `app-mobile/src/main/java/ru/mars/mobile/widget/MarsQuickWidgetProvider.kt`, `app-mobile/src/main/java/ru/mars/mobile/widget/MarsCompactWidgetProvider.kt`, `app-mobile/src/main/res/layout/widget_mars_quick.xml`, `app-mobile/src/main/res/layout/widget_mars_compact.xml`, `app-mobile/src/main/res/drawable/widget_mars_bg.xml`, `app-mobile/src/main/res/drawable/widget_mars_chip_bg.xml` (НОВЫЙ), `app-mobile/src/main/res/values/strings.xml`, `app-mobile/src/test/java/ru/mars/mobile/MarsWidgetActionsPolicyTest.kt` | `ChatActivity.kt`, `ChatInitSection.kt`, `LocalChatActivity.kt`, Gradle/kotlinc |
| **Координатор (Склейка и сборка)** | Координатор | `app-mobile/src/main/java/ru/mars/mobile/LocalChatActivity.kt`, `app-mobile/src/main/java/ru/mars/mobile/ChatInitSection.kt`, `app-mobile/src/main/java/ru/mars/mobile/SplashActivity.kt`, `app-mobile/src/main/java/ru/mars/mobile/MainActivity.kt` | Запускает тесты и релизную сборку после завершения всех 4 воркеров |
