# Контракт Волны 15: Комплексный апгрейд Mars Android (Модели WebUI, Push-навигация, Глубокий космос с Марсом и стеклянный UI)

Дата: 16.11.2026
Репозиторий: `C:/Users/SKY/.zcode/workspace/default/mars-android`
Ветка: `feat/local-chat-wave`

## Общие правила для ВСЕХ воркеров (СТРОГО)

1. **ЗАПРЕТ НА СБОРКУ:** Ни один воркер НЕ запускает `./gradlew`, `gradlew.bat`, `bash build-mars.sh` или самодельный `kotlinc`. Сборку и тесты прогоняет ТОЛЬКО координатор после завершения всех воркеров.
2. **Жёсткое разделение файлов:** Каждый воркер правит ТОЛЬКО файлы из своей таблицы ниже. Чужие файлы читать можно, править — ЗАПРЕЩЕНО.
3. **Маскировка `***` в выводе инструментов:** Если при чтении файла видишь `***` — это автоматическая редакция секретоподобных строк системой, сам файл на диске целый. Не чинить и не искать порчу.
4. **Никаких эмодзи** в строках UI и комментариях (правило дизайн-системы MarsTheme).

---

## Поток 1 (Worker 1): Синхронизация модели чата между WebUI и Android

### Диагноз проблемы
1. На сервере (`api/routes.py:10715`) `GET /api/session?session_id=...` вычисляет `effective_model` и `effective_provider` ТОЛЬКО если передан query-параметр `resolve_model=1` (или если они буквально записаны в объект). Без `resolve_model=1` сессии, где модель резолвится через профиль/дефолт или state.db, отдают `model: null`.
2. В `ModelSelection.kt`:
   - Поле `userPick` грузится из `SharedPreferences` (`PrefsModelChoiceStorage`) с `explicitPick = true` и живёт ВЕЧНО между всеми чатами.
   - `wireChoice()` возвращает `userPick ?: sessionModel ?: current`.
   - В результате: если пользователь когда-либо выбирал модель в приложении (или она сохранилась в `SharedPreferences`), то при открытии ЛЮБОГО чата, где в WebUI работали на другой модели (`detail.model`), `setSessionModel(detail.model, detail.modelProvider)` записывает `sessionModel`, но `buttonLabel()`, `displayedChoice()` и `wireChoice()` игнорируют `sessionModel` и показывают старый `userPick`!
   - Более того, если `detail.model` равен, например, `"claude-sonnet-5.5"` (или `"anthropic/claude-sonnet-5.5"`), а в каталоге `GET /api/models` модель имеет `id = "@custom:plusvibe-api:claude-sonnet-5.5"`, то `lastCatalog?.models?.firstOrNull { it.id == choice.modelId }` не находит строку в каталоге и не показывает человеческое имя `row.name`.
3. Когда пользователь явно меняет модель в открытом диалоге (`chooseModel()`), WebUI сохраняет её в сессию через `POST /api/session/update` (`{"session_id": sid, "model": modelId, "model_provider": providerId}`), а Android только кладёт в локальный `modelSelection`.

### Что должен сделать Worker 1

#### 1. `core-network/src/main/java/ru/mars/network/repository/SessionRepository.kt`
- В `getSessionDetail(baseUrl, credential, sessionId)` добавить `.addQueryParameter("resolve_model", "1")` в URL `api/session`:
  ```kotlin
  val url = baseUrl.toHttpUrl().newBuilder()
      .addPathSegments("api/session")
      .addQueryParameter("session_id", sessionId)
      .addQueryParameter("resolve_model", "1")
      .build()
      .toString()
  ```
- Добавить метод сохранения выбранной модели в сессию на сервере (`POST /api/session/update`):
  ```kotlin
  suspend fun updateSessionModel(
      baseUrl: String,
      credential: ConsumedDeviceCredential,
      sessionId: String,
      model: String,
      modelProvider: String?,
  ): Boolean = withContext(Dispatchers.IO) {
      if (sessionId.isBlank() || model.isBlank()) return@withContext false
      val fields = mutableMapOf<String, Any?>(
          "session_id" to sessionId,
          "model" to model,
      )
      if (!modelProvider.isNullOrBlank()) {
          fields["model_provider"] = modelProvider
      }
      when (sessionOp(baseUrl, credential, "api/session/update", fields)) {
          is WireBody -> true
          is WireError -> false
      }
  }
  ```

#### 2. `app-mobile/src/main/java/ru/mars/mobile/ModelSelection.kt`
- Добавить в `ModelSelection` умный поиск строки каталога `findCatalogModel(modelId: String, providerId: String? = null): ModelInfo?`:
  1. Точное совпадение `id == modelId` (с учётом `providerId`, если задан).
  2. Совпадение для custom-провайдера: если `providerId` начинается с `"custom"` и в каталоге есть `id.equals("@$providerId:$modelId", ignoreCase = true)`.
  3. Нормализованное совпадение (как в WebUI `_findModelInDropdown`): функция нормализации снимает ведущий `@provider:` (`Regex("^@([^:]+:)+")`), снимает ведущий `vendor/` (`Regex("^[^/]+/")`), приводит к lowercase и заменяет `-` на `.`. Если есть строка с тем же `providerId` (или если `providerId == null`, единственная/первая подходящая строка в каталоге) — вернуть её.
- Исправить приоритет при открытии существующей сессии (`setSessionModel`):
  - Когда открывается существующий чат, у которого на сервере УЖЕ есть модель (`setSessionModel(modelId, providerId)` вызван с непустым `modelId`), приложение обязано показывать модель ЭТОЙ сессии (паритет с WebUI!), если только пользователь не выбрал модель вручную В ЭТОЙ ЖЕ сессии.
  - Как устроить чисто и не сломать существующие тесты `ModelSelectionTest`:
    - Внимание! Прочитай `app-mobile/src/test/java/ru/mars/mobile/ModelSelectionTest.kt` и `ChatClickPathFlowTest.kt`!
    - В `ModelSelectionTest` есть тесты:
      - `explicitPickWinsOverServerSessionModelForDisplay`: вызывает `selection.select(...)`, потом `selection.setSessionModel(...)` и проверяет `assertEquals("gemini-3.8-flash", selection.buttonLabel())`.
      - `explicitPickSurvivesServerSessionModelAndKeepsTheFlag`: `select(gptSol())`, потом `setSessionModel("claude-haiku-5.5", ...)` -> `wireChoice()` возвращает `gptSol` с `explicitPick = true`.
      - `restoredPickIsExplicitAndSurvivesSessionBind`: `ModelSelection(storage)` загружает из storage, вызывает `restored.setSessionModel(...)` -> `wireChoice()` возвращает сохранённый.
    - ПОЧЕМУ тогда у владельца при открытии чата из WebUI показывается неверная модель?
      Потому что:
      А) При открытии другого диалога (`loadSessionById` в `ChatSessionSection.kt`) `ChatActivity` НЕ сбрасывает `userPick` от предыдущего диалога (или залипший в `SharedPreferences` выбор трёхдневной давности), а вызывает только `modelSelection.setSessionModel(detail.model, detail.modelProvider)`. Если в `ModelSelection` добавить метод `bindOpenedSessionModel(modelId: String?, providerId: String?)`, который:
         - если `modelId` не пустой: сбрасывает `userPick = null`, `storage.clear()`, ставит `sessionModel = ModelChoice(resolvedId, resolvedProvider, explicitPick = false)` и `current = sessionModel`!
         - а в `ChatSessionSection.kt` при `loadSessionById` и `reloadCurrentSession` и `showOfflineSessionCopy` вызывать `modelSelection.bindOpenedSessionModel(detail.model, detail.modelProvider)` (или если `detail.model` не пустой — вызывать `bindOpenedSessionModel`, иначе `setSessionModel`)!
         - Давай проверим: когда человек нажимает на чат в списке диалогов (`loadSessionById`), он открывает чат, в котором работал в WebUI. В этом чате на сервере стоит модель X (`detail.model`). Если мы вызываем `bindOpenedSessionModel(detail.model, detail.modelProvider)`:
           1) когда `detail.model` не пустой — старый глобальный `userPick` сбрасывается (`clearExplicit()`), `sessionModel` становится равным модели этой сессии (причём если в каталоге есть соответствующий `ModelInfo`, берётся его канонический `providerId`), и на кнопке `modelBtn` отображается человеческое имя модели из каталога (`row.name`)!
           2) а если человек ПОСЛЕ открытия этого чата нажмёт на чип модели и выберет новую модель в пикере (`chooseModel()` -> `modelSelection.select(...)`), то:
              - `userPick` станет `explicitPick = true` для следующих ходов этого чата,
              - И если `currentSessionId != null`, `chooseModel()` сразу отправит `SessionRepository().updateSessionModel(BASE_URL, c, sid, picked.modelId, picked.providerId)` на сервер, чтобы сессия и в WebUI сразу знала новую модель!
           3) При этом старый метод `setSessionModel(modelId, providerId)` остаётся с прежней семантикой для первого хода нового чата (`ChatTurnSection.kt:178`, где сервер только что создал пустую сессию с дефолтом, а у пользователя был выбран `userPick` до первого сообщения) и все существующие тесты в `ModelSelectionTest` и `ChatClickPathFlowTest` остаются 100% зелёными!
      Б) Вторая причина, почему кнопка модели и пикер показывают не ту модель:
         В `ModelSelection.buttonLabel()` и `ChatModelSection.chooseModel()`:
         - `buttonLabel()` искал `lastCatalog?.models?.firstOrNull { it.id == choice.modelId }`. Если сервер в `GET /api/session` вернул `model = "claude-sonnet-5.5"`, `model_provider = "custom:plusvibe-api"`, а в каталоге `GET /api/models` модель записана как `id = "@custom:plusvibe-api:claude-sonnet-5.5"`, `name = "Claude Sonnet 5.5"`, то `it.id == choice.modelId` возвращал `null`! И кнопка показывала сырой id или вообще не находила отмеченный пункт в радио-списке `chooseModel()` (`entries.indexOfFirst { it.model.id == modelSelection.displayedChoice()?.modelId }` давал `-1`)!
         - Используй `findCatalogModel(choice.modelId, choice.providerId)` и в `buttonLabel()`, и в `matchesCatalogModel(entry.model, displayedChoice)` (или нормализуй `sessionModel` при наличии `lastCatalog` и в `resolveEffective`/`pickerEntries`).
         - Важно: в `ModelSelectionTest.selectReturnsRealIdAndProviderAndPersists` и `adoptsServerDefaultModelWithCatalogProvider` проверь, что `buttonLabel()` возвращает `row.name` (если `name` не пустой), иначе `choice.modelId`. В `ModelSelectionTest` у `gemini-3.8-flash` `name = "gemini-3.8-flash"`, а у `@custom:plusvibe-api:gpt-6.1-sol` `name = "GPT 6.1 SOL"`. Поэтому `findCatalogModel` не сломает ни один тест, но починит сопоставление `claude-sonnet-5.5` ↔ `@custom:plusvibe-api:claude-sonnet-5.5`!

#### 3. Файлы Worker 1
- Правит ТОЛЬКО:
  - `core-network/src/main/java/ru/mars/network/repository/SessionRepository.kt`
  - `app-mobile/src/main/java/ru/mars/mobile/ModelSelection.kt`
  - `app-mobile/src/main/java/ru/mars/mobile/ChatModelSection.kt`
  - `app-mobile/src/main/java/ru/mars/mobile/ChatSessionSection.kt`
  - `app-mobile/src/test/java/ru/mars/mobile/ModelSelectionTest.kt`
- НЕ трогает никакие другие файлы.

---

## Поток 2 (Worker 2): Мгновенное открытие нужного чата по тапу на пуш-уведомление

### Диагноз проблемы
1. На сервере (`api/push_fcm.py:345` и `api/streaming.py:12470`) при завершении ответа отправляется FCM-сообщение:
   ```json
   {
     "notification": {"title": "Марс: ответ готов", "body": "..."},
     "data": {"ts": "...", "url": "session/<sid>", "tag": "hermes-<sid>"}
   }
   ```
2. Здесь СРАЗУ ТРИ разрыва:
   - **Разрыв А (Фоновый системный тап через `SplashActivity`):** Так как в FCM-сообщении есть блок `"notification"`, когда приложение в фоне или убито, Android System Tray сам показывает уведомление (не вызывая `MarsFirebaseMessagingService.onMessageReceived`). Когда пользователь тапает по этому системному уведомлению, Android запускает `LAUNCHER`-активность — `SplashActivity` — и кладёт все ключи `data` (`url = "session/<sid>"`, `tag = "hermes-<sid>"`, либо `session_id`) прямо в `intent.extras` `SplashActivity`!
     А что делает `SplashActivity.navigate()`?
     ```kotlin
     startActivity(Intent(this, target))
     ```
     Она создаёт ПУСТОЙ `Intent` и выбрасывает все `intent.extras` и `intent.data`! Поэтому `ChatActivity` стартует с чистым интентом и открывает пустой «Новый чат»!
   - **Разрыв Б (`MainActivity.openChat()`):** Если маршрут прошёл через `MainActivity`, `openChat()` тоже делает `startActivity(Intent(this, ChatActivity::class.java))` без копирования `intent.extras` / `intent.data`.
   - **Разрыв В (`ChatSessionSection.restoreOpenSession` и `ChatActivity.onNewIntent`):**
     `ChatActivity` проверяет только `intent.getStringExtra(ChatActivity.EXTRA_OPEN_SESSION_ID)`. Но если интент пришёл от системного FCM-тапа (или через `SplashActivity`), в `extras` нет `extra_open_session_id` — там лежат сырые ключи FCM `data`: `session_id`, `url` (`"session/<sid>"` или `"mars://chat/session/<sid>"`), `tag` (`"hermes-<sid>"`) или `intent.dataString`!
   - **Разрыв Г (`PushTapRouting`):**
     Сейчас `PushTapRouting.sessionIdFromUrl(url)` ищет `"session/"` — для `"session/abc"` он вернёт `"abc"`, но если в пуше есть `tag = "hermes-<sid>"` и нет `session_id`/`url`, или если надо достать сессию из произвольной карты extras (`extractSessionId`), единого метода нет. Кроме того, в `PushMessagePolicy.parse` поле `sessionId` заполнялось только из `data["session_id"]`, а когда сервер шлёт `url = "session/<sid>"` (как в `streaming.py:12472`), `spec.sessionId` оставался `null`! Из-за этого:
     1) в foreground-уведомлении даже кнопка «Ответить» (`replyAction`) не появлялась, потому что `spec.sessionId == null`!
     2) `tapIntent` спасался только фолбэком `PushTapRouting.sessionIdFromUrl(spec.url)`.

### Что должен сделать Worker 2

#### 1. `core-network/src/main/java/ru/mars/network/repository/PushMessagePolicy.kt` и `PushTapRouting.kt`
- В `PushTapRouting`:
  - Добавить метод `sessionIdFromTag(tag: String?): String?`: если `tag` (после trim) начинается с `"hermes-"` и не равен `"hermes-push-test"`, вернуть хвост после `"hermes-"` (если он не пустой и не содержит пробелов/`/`).
  - Добавить универсальный метод:
    ```kotlin
    fun extractSessionId(
        explicitExtra: String? = null,
        sessionId: String? = null,
        url: String? = null,
        tag: String? = null,
    ): String?
    ```
    который возвращает первый непустой id по приоритету:
    1) `explicitExtra?.trim()?.takeIf { it.isNotEmpty() }`
    2) `sessionId?.trim()?.takeIf { it.isNotEmpty() }`
    3) `sessionIdFromUrl(url)`
    4) `sessionIdFromTag(tag)`
- В `PushMessagePolicy.parse`:
  - Вычислять `resolvedSessionId`:
    ```kotlin
    val url = data[KEY_URL]?.trim()?.takeIf { it.isNotEmpty() }
    val tag = data[KEY_TAG]?.trim()?.takeIf { it.isNotEmpty() }
    val sessionId = PushTapRouting.extractSessionId(
        sessionId = data[KEY_SESSION_ID],
        url = url,
        tag = tag,
    )
    ```
    И передавать `sessionId = sessionId` в `PushNotificationSpec`! Тогда и foreground-уведомления от `streaming.py` (`url="session/<sid>"`) получат валидный `spec.sessionId` и кнопку «Ответить», и правильный `tapIntent`.

#### 2. `app-mobile/src/main/java/ru/mars/mobile/SplashActivity.kt` и `MainActivity.kt`
- В `SplashActivity`:
  - Когда `SplashActivity` запускается из тапа по пушу (в `intent` уже есть целевой sessionId, определяемый через `PushTapRouting.extractSessionId(explicitExtra = intent?.getStringExtra(ChatActivity.EXTRA_OPEN_SESSION_ID), sessionId = intent?.getStringExtra("session_id") ?: intent?.getStringExtra(PushReplyPolicy.EXTRA_SESSION_ID), url = intent?.getStringExtra("url") ?: intent?.dataString, tag = intent?.getStringExtra("tag"))`), прокидывать все `intent?.extras` и `intent?.data` в целевой `Intent(this, target)` в `navigate()`, И явно класть `putExtra(ChatActivity.EXTRA_OPEN_SESSION_ID, pushSessionId)`, если `pushSessionId != null`!
  - Более того: если `hasCredential == true` и `pushSessionId != null` (человек тапнул по пушу конкретного чата), не заставлять его ждать `MIN_VISIBLE_MS` на заставке — в `scheduleRoute()` при наличии `pushSessionId` и `targetReady` сразу переходить (`leave()` без фейда или с мгновенным переходом), чтобы нужный чат открывался мгновенно! (Обязательно проверь, что `SplashPolicyTest` и `SplashExitContractTest` тестируют `SplashPolicy` напрямую — саму `SplashPolicy` НЕ менять, чтобы не сломать её тесты!).
- В `MainActivity.openChat()`:
  - Пробрасывать `intent?.extras` и `intent?.data` в `Intent(this, ChatActivity::class.java)` (и если через `PushTapRouting.extractSessionId(...)` найден sessionId — класть `ChatActivity.EXTRA_OPEN_SESSION_ID`), чтобы даже после логина или маршрута через `MainActivity` целевой чат не терялся.

#### 3. `app-mobile/src/main/java/ru/mars/mobile/ChatActivity.kt` (секция `onNewIntent`)
- Внимание: `ChatSessionSection.kt` правит Worker 1, поэтому Worker 2 НЕ трогает `ChatSessionSection.kt`!
- Где тогда извлекать `session_id` из сырых FCM extras для `ChatActivity`?
  - В `ChatActivity.onCreate` ПЕРЕД вызовом `restoreOpenSession(savedInstanceState)`!
    Если в `intent` ещё нет `EXTRA_OPEN_SESSION_ID`, но `PushTapRouting.extractSessionId(...)` нашёл сессию в `intent` (`"session_id"`, `"url"`, `intent.dataString`, `"tag"`, `PushReplyPolicy.EXTRA_SESSION_ID`), то прямо в `ChatActivity.onCreate` делаем:
    ```kotlin
    resolvePushSessionId(intent)?.let { sid ->
        intent.putExtra(EXTRA_OPEN_SESSION_ID, sid)
    }
    ```
    Тогда `restoreOpenSession(savedInstanceState)` в `ChatSessionSection.kt` прочитает `intent.getStringExtra(ChatActivity.EXTRA_OPEN_SESSION_ID)` и сразу вызовет `loadSessionById(sid)`!
  - И в `ChatActivity.onNewIntent(intent: Intent)`:
    ```kotlin
    val requested = resolvePushSessionId(intent).orEmpty()
    if (requested.isNotEmpty()) {
        intent.putExtra(EXTRA_OPEN_SESSION_ID, requested)
        if (requested != currentSessionId) {
            loadSessionById(requested)
        }
    }
    ```
  - Вспомогательный метод в `ChatActivity.kt`:
    ```kotlin
    internal fun resolvePushSessionId(src: Intent?): String? {
        if (src == null) return null
        return ru.mars.network.repository.PushTapRouting.extractSessionId(
            explicitExtra = src.getStringExtra(EXTRA_OPEN_SESSION_ID),
            sessionId = src.getStringExtra("session_id")
                ?: src.getStringExtra(PushReplyPolicy.EXTRA_SESSION_ID),
            url = src.getStringExtra("url") ?: src.dataString,
            tag = src.getStringExtra("tag"),
        )
    }
    ```

#### 4. Файлы Worker 2
- Правит ТОЛЬКО:
  - `core-network/src/main/java/ru/mars/network/repository/PushTapRouting.kt`
  - `core-network/src/main/java/ru/mars/network/repository/PushMessagePolicy.kt`
  - `core-network/src/test/java/ru/mars/network/repository/PushTapRoutingTest.kt`
  - `core-network/src/test/java/ru/mars/network/repository/PushMessagePolicyTest.kt`
  - `app-mobile/src/main/java/ru/mars/mobile/SplashActivity.kt`
  - `app-mobile/src/main/java/ru/mars/mobile/MainActivity.kt`
  - `app-mobile/src/main/java/ru/mars/mobile/ChatActivity.kt`
- НЕ трогает `ChatSessionSection.kt`, `SplashPolicy.kt`, `MarsTheme.kt` и другие файлы.

---

## Поток 3 (Worker 3): Анимированный 3D/GLSL фон «Глубокий космос с красной планетой Марс»

### Задача
В проекте Квартал (`C:/Users/SKY/.zcode/workspace/default/stavropol-sim/public/chat-bg.js`, строки 8–189 и 1380–1545) реализован крутой глубокий анимированный фон космоса (`_CS_VERT_300` + `_CS_FRAG_300` + кометы с плазменным хвостом):
- Глубокий космический фон (Void Indigo / Obsidian)
- Медленное вращение небосвода вокруг дальней полярной оси + мягкий параллакс
- Объёмная туманность с domain warping (`fbm3`/`fbm5`) и тёмными пылевыми прожилками (`dustLane`, Bok globules, `transmittance`, `rimLight`)
- Многослойное вращающееся звёздное поле (`starLayer` x4 слоя глубины) с мерцанием и крестообразными вспышками ярких звёзд
- Силуэт планеты на горизонте с контровым светом (eclipse limb), атмосферным рассеянием (Rayleigh halo) и процедурным рельефом `fbm3(nrm * 4.2 + ...)`
- Кинематографическая виньетка и ACES Filmic тональная компрессия (`acesTone`)
- Падающие кометы с плазменным хвостом.

В Android Марсе нужно реализовать **точно такой же глубокий анимированный фон космоса**, но чтобы планета была **красной планетой Марс**:
- Вместо холодного синего лимба из Квартала сделать **марсианскую палитру планеты и туманности**:
  - В туманности усилить благородные рубиново-янтарные и глубокие пурпурно-обсидиановые оттенки (сохранив глубокий тёмный космос, чтобы текст отлично читался).
  - Планета Марс (`pCenter`, `pRad = 0.24`):
    - Атмосферное свечение лимба (`atmoGlow * crescent`): тёплый красно-янтарный ореол пылевой атмосферы Марса (`vec3(0.92, 0.28, 0.14)` и `vec3(1.0, 0.45, 0.22)`).
    - Освещённая поверхность (`pLit`): терракотово-ржавый и тёмно-багровый марсианский рельеф (`mix(vec3(0.32, 0.08, 0.04), vec3(0.78, 0.24, 0.10), pTex)` с детальными кратерами/каньонами через `fbm5`/`fbm3`), тёмная сторона `pDark = vec3(0.015, 0.005, 0.006)`, яркий рубиново-огненный серп на лимбе (`vec3(0.96, 0.36, 0.18) * rim`).
- Плюс прямо в самом GLSL-шейдере (или вторым легковесным проходом) добавить периодический пролёт комет с мягким хвостом, чтобы вся анимация работала в одном GPU-шейдере без лишнего Canvas-оверлея!

### Архитектура и файлы Worker 3 (ТОЛЬКО новые файлы, без конфликтов!)

1. **`app-mobile/src/main/java/ru/mars/mobile/MarsSpaceShaderPolicy.kt`** (чистый JVM-объект, БЕЗ `import android.*`!):
   - Константы вершинного (`VERTEX_SHADER_300`) и фрагментного (`FRAGMENT_SHADER_300`) шейдеров `#version 300 es` (и резервный `#version 100` `VERTEX_SHADER_100` / `FRAGMENT_SHADER_100` или `GLSL` совместимый, чтобы работало на 100% устройств).
   - Чистые функции расчёта времени, FPS-троттлинга (24 fps в покое = 41 мс, 45–60 fps при скролле), расчёта параллакса `smoothScroll(rawScrollPx: Int, currentSmooth: Float, dtSec: Float): Float` (через `tanh(rawScrollPx / 850f) * 1.4f`), проверки `shouldAnimate(animatorsEnabled: Boolean, powerSave: Boolean, animatorScale: Float): Boolean`.
   - Параметры Марса (`PLANET_RADIUS`, цвета атмосферы и поверхности) вынесены константами/методами, чтобы тестировались на JVM.

2. **`app-mobile/src/main/java/ru/mars/mobile/MarsSpaceBackgroundView.kt`**:
   - Кастомная Android `FrameLayout` (или `View`/`GLSurfaceView`-обёртка), которую легко подложить первым ребёнком в любой экран:
     - Внутри создаёт `GLSurfaceView` с `setEGLContextClientVersion(3)` (с fallback на ES 2.0 / или программный Canvas-рендерер со звёздами, туманностью и красной планетой Марс, если GL контекст не поднялся или в режиме энергосбережения).
     - Важно: `RENDERMODE_WHEN_DIRTY` + тикер на `Choreographer` с троттлингом из `MarsSpaceShaderPolicy` (24 fps в покое, при `reducedMotion` рисует 1 статичный кадр и останавливает тикер!).
     - Автоматически ставит `onPause()` / `onResume()` в `onAttachedToWindow` / `onDetachedFromWindow` и по `onWindowVisibilityChanged(visibility)` — когда экран уходит в фон, GPU-рендер полностью спит (0% нагрузки на батарею).
     - Публичный статический хелпер:
       ```kotlin
       companion object {
           /**
            * Оборачивает корневую View экрана в FrameLayout с живым космическим фоном Марса
            * позади контента. Идемпотентно.
            */
           fun wrap(context: Context, contentView: View): FrameLayout
       }
       ```
       Как работает `wrap(context, contentView)`:
       Создаёт `FrameLayout(context)`, первым ребёнком кладёт `MarsSpaceBackgroundView(context)` (`MATCH_PARENT, MATCH_PARENT`), вторым ребёнком — `contentView` (`MATCH_PARENT, MATCH_PARENT`), и возвращает этот `FrameLayout`.

3. **`app-mobile/src/test/java/ru/mars/mobile/MarsSpaceShaderPolicyTest.kt`**:
   - Полный набор JVM unit-тестов на `MarsSpaceShaderPolicy`: проверка валидности GLSL-исходников (наличие `#version`, `uRes`, `uTime`, `uScroll`, функций `fbm5`, `starLayer`, `acesTone`, марсианской красной палитры планеты, комет), проверка `shouldAnimate` (reduce motion, power save), проверка `smoothScroll` и кадрового интервала.

#### Файлы Worker 3
- Создаёт ТОЛЬКО:
  - `app-mobile/src/main/java/ru/mars/mobile/MarsSpaceShaderPolicy.kt`
  - `app-mobile/src/main/java/ru/mars/mobile/MarsSpaceBackgroundView.kt`
  - `app-mobile/src/test/java/ru/mars/mobile/MarsSpaceShaderPolicyTest.kt`
- НЕ трогает существующие файлы!

---

## Поток 4 (Worker 4): Полупрозрачные стеклянные поверхности (Glass UI) на всех окнах и страницах

### Задача
Сделать все окна, панели, карточки, диалоги, боковое меню и страницы полупрозрачными («глубокое обсидиановое стекло»), чтобы сквозь них мягко просвечивал глубокий космос и красная планета Марс, при этом **не сломав ни один существующий тест**!

### КРИТИЧЕСКИЕ ОГРАНИЧЕНИЯ из существующих тестов (ПРОЧИТАТЬ ВНИМАТЕЛЬНО!)
В `app-mobile/src/test/java/ru/mars/mobile/MarsThemeDepthTest.kt` жестко зашиты проверки на `MarsGradients`:
1. `MarsGradients.BG == 0xff07080b.toInt()`, `SURFACE_0 == 0xff0a0d13.toInt()`, `SURFACE_1 == 0xff0f131c.toInt()`, `SURFACE_2 == 0xff151b27.toInt()`, `SURFACE_3 == 0xff1c2333.toInt()` — и тест `all depth layers are fully opaque` проверяет `MarsGradients.DEPTH_LAYERS.forEach { assertTrue(MarsGradients.isOpaque(it)) }`!
   -> **ЗНАЧИТ:** константы `BG`, `SURFACE_0..3` и `DEPTH_LAYERS` в `MarsGradients.kt` и их алиасы `MarsTheme.DEPTH_BG`, `MarsTheme.BG`, `MarsTheme.SURFACE_0..3` менять НЕЛЬЗЯ!
2. В `MarsThemeDepthTest`:
   - `TOOL_CARD`: проверяется `assertEquals(0xff0a0d14.toInt(), spec.startColor)` и `assertTrue(MarsGradients.isOpaque(spec.startColor))` — `TOOL_CARD` не трогать!
   - `COMPOSER_CAPSULE`: проверяется `assertEquals(0xff131722.toInt(), spec.startColor)`, `assertEquals(0xff0d1017.toInt(), spec.endColor)`, `assertEquals(0xff252d3f.toInt(), spec.strokeColor)` — константу `COMPOSER_CAPSULE` в `MarsGradients` не менять!
   - `USER_BUBBLE`: уже полупрозрачный (`0xe01e2638`, alpha >= 0xCC).
   - `BOT_CARD`: уже полупрозрачный (`0x991b2231`, alpha >= 0x99).
3. В `LiveRunStatusPresenterTest.kt` и `ToolCardPresenterTest.kt`:
   - Проверяется `assertEquals(MarsTheme.SURFACE_CARD, view.cardColor)` и `assertEquals(MarsTheme.CODE_BG, done.cardColor)` — сами ссылки на константы там символические, но лучше понимать, какие константы реально рисуют UI!

### Что ИМЕННО нужно сделать полупрозрачным в `MarsTheme.kt` и экранах:
1. В `MarsTheme.kt`:
   - Ни один тест не проверяет конкретный hex у `MarsTheme.SIDEBAR`, `MarsTheme.SURFACE`, `MarsTheme.SURFACE_SUBTLE`, `MarsTheme.SURFACE_CARD`, `MarsTheme.BOT_CARD`, `MarsTheme.CODE_BG`, `MarsTheme.QUOTE_BG`, `MarsTheme.SCREEN_VEIL`! (Тесты сравнивают только символы `MarsTheme.SURFACE_CARD == MarsTheme.SURFACE_CARD`).
   - Перевести палитру поверхностей `MarsTheme` в благородное полупрозрачное обсидиановое стекло (как в Квартале на 3D-сценах, где карточки имеют ~72–82% непрозрачности, чтобы и 3D-космос с планетой просвечивал, и белый текст идеально читался):
     - `SCREEN_VEIL = 0x3805070c.toInt()` (~22% лёгкая вуаль вместо глухих 42%, чтобы космос и красная планета Марс были сочными и глубокими на всех страницах!).
     - `SIDEBAR = 0xd80a0d14.toInt()` (~85% полупрозрачное стекло для боковой шторки разделов).
     - `SURFACE = 0xb810141e.toInt()` (~72% полупрозрачное стекло для карточек, инпутов, кнопок шапки и панелей).
     - `SURFACE_SUBTLE = 0xa8141926.toInt()` (~66% полупрозрачное стекло для чипов, подложек и вторичных плашек).
     - `SURFACE_CARD = 0xc4151b28.toInt()` (~77% полупрозрачное стекло для карточек диалогов, проектов, задач, мини-плеера).
     - `GLASS_FILL = 0xb810141e.toInt()` (~72% стекло).
     - `BOT_CARD = 0xb00f131c.toInt()`, `CODE_BG = 0xcc080a10.toInt()`, `QUOTE_BG = 0xb0141822.toInt()`.
     - Добавить в `MarsTheme` новые стеклянные токены для чипов композера и модалок:
       - `const val GLASS_PANEL = 0xd010141e.toInt()` (~81% для модальных окон и шторок: `MarsDialog`, `ArtifactSheet`, `LocalTurnSettingsSheet`, `TranslateSheet`, `VoiceProsodySheet`, `LocalChatActionsSheet`, `LocalChatEditSheet`, `ChatMessageEditSheet`)
       - `const val GLASS_CHIP = 0xa8121723.toInt()` (~66% для чипов в композере и шапке)
   - В `MarsTheme.composerCapsule(context: Context)`:
     - Сейчас он вызывает `gradientCard(context, MarsGradients.COMPOSER_CAPSULE)`, где цвета непрозрачные (`0xff131722`). Чтобы тест `MarsThemeDepthTest` на `MarsGradients.COMPOSER_CAPSULE` остался зелёным, а сама капсула композера на экране стала полупрозрачной стеклянной, пусть `MarsTheme.composerCapsule(context)` строит градиент с полупрозрачными цветами (`0xc8131722.toInt()` -> `0xc80d1017.toInt()`, stroke `0x55384664`)!

2. В `MarsDialog.kt` и шторках (`ArtifactSheet.kt`, `LocalTurnSettingsSheet.kt`, `TranslateSheet.kt`, `VoiceProsodySheet.kt`, `LocalChatActionsSheet.kt`, `LocalChatEditSheet.kt`, `ChatMessageEditSheet.kt`):
   - В `MarsDialog.show`: карточка диалога использует `MarsTheme.GLASS_PANEL` (или `MarsTheme.SURFACE`, который теперь полупрозрачный `0xb810141e`), а `setDimAmount(0.55f)` вместо `0.72f`, чтобы за диалогом был виден живой космос.
   - Проверить шторки (`*Sheet.kt`): если где-то захардкожен непрозрачный цвет `0xff...` или `MarsTheme.BG` / `MarsTheme.SURFACE_0..2` как фон карточки/шторки, заменить на `MarsTheme.GLASS_PANEL` или `MarsTheme.SURFACE_CARD`.

3. В `ChatScreenScaffold.kt` и `ChatTranscriptSection.kt`:
   - В `ChatScreenScaffold.kt` чипы композера (`modelBtn`, `reasoningBtn`, `activityModeBtn`, `workspaceBtn`, `toolsetsChip`, `quotaChip`, `usageIndicator`) и плашки используют `MarsTheme.SURFACE_1` или `MarsTheme.SURFACE_0` (которые по тесту `MarsThemeDepthTest` обязаны оставаться opaque `0xff...`). Заменить в `ChatScreenScaffold.kt` и `ChatTranscriptSection.kt` использование `MarsTheme.SURFACE_0` / `MarsTheme.SURFACE_1` на `MarsTheme.SURFACE_SUBTLE` / `MarsTheme.SURFACE_CARD`! (Проверить по `ChatClickPathContractTest` и `ChatAccessibilityContractTest` — они НЕ проверяют `SURFACE_1` в `ChatScreenScaffold.kt`).

#### Файлы Worker 4
- Правит ТОЛЬКО:
  - `app-mobile/src/main/java/ru/mars/mobile/MarsTheme.kt`
  - `app-mobile/src/main/java/ru/mars/mobile/MarsDialog.kt`
  - `app-mobile/src/main/java/ru/mars/mobile/ChatScreenScaffold.kt`
  - `app-mobile/src/main/java/ru/mars/mobile/ChatTranscriptSection.kt`
  - `app-mobile/src/main/java/ru/mars/mobile/ArtifactSheet.kt`
  - `app-mobile/src/main/java/ru/mars/mobile/LocalTurnSettingsSheet.kt`
  - `app-mobile/src/main/java/ru/mars/mobile/TranslateSheet.kt`
  - `app-mobile/src/main/java/ru/mars/mobile/VoiceProsodySheet.kt`
  - `app-mobile/src/main/java/ru/mars/mobile/LocalChatActionsSheet.kt`
  - `app-mobile/src/main/java/ru/mars/mobile/LocalChatEditSheet.kt`
  - `app-mobile/src/main/java/ru/mars/mobile/ChatMessageEditSheet.kt`
- НЕ трогает `MarsGradients.kt` (чтобы `MarsThemeDepthTest` остался 100% зелёным), НЕ трогает `SectionScaffold.kt` и `ChatShellSection.kt` (их подключит к `MarsSpaceBackgroundView` сам координатор после завершения воркеров!).

---

## Что делает Координатор после завершения воркеров 1–4
1. Подключает `MarsSpaceBackgroundView` во все экраны приложения (`SectionScaffold.kt`, `ChatShellSection.kt`, `ChatSessionsActivity.kt`, `SettingsActivity.kt`, `MusicShellSection.kt`, `MainActivity.kt`, `SplashActivity.kt`), проверяя сохранность всех `*ShellContractTest`.
2. Запускает полную сборку и прогон всех unit-тестов (`:core-network:testDebugUnitTest`, `:app-mobile:testDebugUnitTest`).
3. Собирает подписанный боевым ключом `assembleRelease`, проверяет `mapping.txt` и ставит APK на подключённый POCO F5 (`6d6fe9f6`).
