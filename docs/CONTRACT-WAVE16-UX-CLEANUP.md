# Контракт Волны 16: Комплексный UX-апгрейд по 7 пунктам владельца

Ветка: `feat/local-chat-wave` (`C:/Users/SKY/.zcode/workspace/default/mars-android`).
Важно: **Воркерам запрещено запускать `./gradlew` / `bash build-mars.sh`** — компиляцию, прогон тестов и сборку APK делает только координатор!

---

## Поток 1: Исправление краша Настроек (пункт 1) + Старт приложения со страницы «Диалоги» (пункт 7)

### 1.1. Краш при открытии `SettingsActivity`
- **Причина (подтверждена `adb logcat -b crash`)**:
  `IllegalStateException: This view is not a TextView (m.m2 = androidx.appcompat.widget.SwitchCompat requires Theme.AppCompat)` на `SettingsActivity.kt:431` в `buildWakeWordRow()`.
  Темы приложения (`@style/AppTheme` в `res/values/styles.xml`) наследуют `android:style/Theme.Material.NoActionBar` (платформенный Material, а не AppCompat). Когда `SwitchCompat(this)` создаётся с не-AppCompat контекстом, конструктор `SwitchCompat` падает в `ThemeEnforcement.checkAppCompatTheme`.
- **Решение**:
  1. В `SettingsActivity.kt`:
     - Удалить `import androidx.appcompat.widget.SwitchCompat`.
     - В `private lateinit var wakeSwitch: SwitchCompat` и в `buildWakeWordRow()` заменить `SwitchCompat` на платформенный `android.widget.Switch(this)` со стилизацией `thumbTintList` и `trackTintList` (цвета `MarsTheme.ACCENT` / `MarsTheme.TEXT_MUTED` для thumb и `MarsTheme.ACCENT_BG` / `MarsTheme.SURFACE_SUBTLE` для track через `ColorStateList`), чтобы он гармонично выглядел в тёмной стеклянной теме и никогда не требовал `Theme.AppCompat`.
     - Проверить `SettingsSourceContractTest.kt` — убедиться, что все проверки контракта `SettingsSourceContractTest` соблюдены.

### 1.2. При запуске приложения открывать страницу «Диалоги» (`ChatSessionsActivity`), а не пустой «Новый чат»
- **Причина**:
  Сейчас `SplashActivity.goNext()` при `SplashRoute.Destination.CHAT` (когда нет целевого пуш-уведомления) создаёт `Intent(this, ChatActivity::class.java)`, а `MainActivity.openChat()` после логина/при наличии токена тоже запускает `ChatActivity` без `sessionId`, если в `intent` нет пуш-сессии.
- **Решение**:
  1. В `SplashActivity.kt` в `goNext()`:
     - Проверить, есть ли в `intent` целевая сессия пуша: `val pushSid = PushTapRouting.extractSessionId(explicitExtra = intent.getStringExtra(ChatActivity.EXTRA_OPEN_SESSION_ID), sessionId = intent.getStringExtra("session_id") ?: intent.getStringExtra(PushReplyPolicy.EXTRA_SESSION_ID), url = intent.getStringExtra("url") ?: intent.dataString, tag = intent.getStringExtra("tag"))` или `PushReplyPolicy.matchesAction(intent.action)`.
     - Если `destination == SplashRoute.Destination.CHAT`:
       - Когда `pushSid != null` или `PushReplyPolicy.matchesAction(intent.action)` — открываем сразу `ChatActivity::class.java` (и вызываем `forwardPushExtras(target)`), чтобы тап по пушу сразу вёл в нужный чат!
       - Иначе (обычный запуск приложения) — открываем страницу «Диалоги»: `Intent(this, ChatSessionsActivity::class.java)`!
  2. В `MainActivity.kt` в `openChat()`:
     - Проверить `val pushSid = PushTapRouting.extractSessionId(...)` или `PushReplyPolicy.matchesAction(intent?.action)`.
     - Если есть `pushSid` или `PushReplyPolicy.matchesAction` — открываем `Intent(this, ChatActivity::class.java)` с пробросом extras.
     - Иначе — открываем `Intent(this, ChatSessionsActivity::class.java)`!
     - Важно: `ChatClickPathContractTest` проверяет `Regex("fun openChat\\(\\)[\\s\\S]{0,300}ChatActivity::class\\.java").containsMatchIn(main)`! Поэтому в теле `fun openChat()` обязательно должна присутствовать ссылка на `ChatActivity::class.java` (в ветке пуш-перехода) и `ChatSessionsActivity::class.java` (в ветке обычного запуска) в пределах первых 300 символов тела `openChat()`.

---

## Поток 2: Расчистка над полем ввода (пункт 2) + Полоса контекста под шапкой и кнопка окна контекста со шторкой (пункты 3 и 5) + Звезда на кнопке отправки (пункт 3)

### 2.1. Над полем ввода — только 3 быстрых бабла «Ок», «Продолжай», «Финиш» (как в WebUI)
- **Требование владельца**:
  «в открытом чате слишком много хлама, к примеру над полем ввода куча всего, там должно быть всего 3 быстрых бабла ок, продолжай, финиш - как это реализовано в веб версии».
- **Решение**:
  1. В `ChatScreenScaffold.kt`:
     - Статическую плашку `quickChipsScroll` (где дублировались 3 кнопки `Ок`, `Продолжай`, `Финиш` одновременно со `smartChipsRow`) скрыть (`visibility = View.GONE`), чтобы над полем ввода никогда не было двух рядов чипов!
     - Оставить одну аккуратную строку `smartChipsRow` прямо над капсулой ввода.
  2. В `ChatComposerSection.kt` в `renderSmartChips()`:
     - Вместо показа стартовых тем на пустом чате и 6 чипов (`Ок, Продолжай, Откат, Поясни, План, Статус`) после ответа — показывать над полем ввода (когда есть хотя бы один ответ ассистента и стрим не печатает, т.е. `hasReply && uiState.status != ChatStatus.TYPING`) **ровно 3 быстрых бабла как в WebUI**:
       1) **«Ок»** (`R.drawable.ic_check`) → отправляет `"Ок, принимаю предложенный план, реализуй"` (или `QuickChips.OK.message`)
       2) **«Продолжай»** (`R.drawable.ic_play_arrow`) → отправляет `"Продолжай"`
       3) **«Финиш»** (`R.drawable.ic_close`) → вызывает `finishSession()` (отправляет `"Ок, закрываю чат"` и закрывает сессию, как в WebUI).
     - Когда чат пустой или идёт генерация (`TYPING`) — `smartChipsRow.visibility = View.GONE`.
     - Примечание для контрактных тестов (`ChatClickPathContractTest`):
       - Сохранить в `ChatComposerSection.kt` вызов `SmartSuggestionsPolicy.suggestionsFor(...)` и переменную `val emptyChat = uiState.messages.isEmpty()` и сигнатуру `internal fun ChatActivity.buildRibbonChip(label: String, iconRes: Int, message: String): Button` (тест `empty chat - starter suggestions render in the chips ribbon` ищет эти подстроки в `chatUi`), но фактически в `smartChipsRow` рендерить ровно 3 чипа: «Ок», «Продолжай», «Финиш»!

### 2.2. Убрать полосу контекста (`contextRow`) и свалку чипов: сделать одну аккуратную кнопку окна контекста внизу (пункты 3 и 5) + оставить под рукой только Контекст, Скрепку, Голосовой ввод, Кнопку отправки со звездой
- **Требование владельца**:
  - «раскидано под полем ввода куча мелких кнопок, нужно всё второстепенное скомпоновать и аккуратно разместить в кнопке контекста как в вэб версии, оставить под рукой только необходимое, контекст, скрепка, голосовой ввод, кнопка отправки(она кстати топорная, нужно реализовать звезду как в вэбе»
  - «полоса под верхней шапкой которая раскрывает окно контекста, убрать вообще от туда, пусть внизу будет одна кнопка окна контекста в которой всё будет аккуратно по полочкам разложено»
- **Решение**:
  1. **Звезда на кнопке отправки (`res/drawable/ic_send.xml`)**:
     - Заменить вектор бумажного самолётика в `app-mobile/src/main/res/drawable/ic_send.xml` на точную копию фирменной звезды Марса с орбитальным кольцом из WebUI (`mars-send-star` из `/opt/hermes-webui/static/index.html:897`):
       - Четырёхлучевая звезда `M12,2.5 C12,7.2 14.2,9.8 20.5,12 C14.2,14.2 12,16.8 12,21.5 C12,16.8 9.8,14.2 3.5,12 C9.8,9.8 12,7.2 12,2.5 Z` с мягкой заливкой и контуром,
       - Наклонённое под `-28°` орбитальное эллиптическое кольцо (`rx=9, ry=3.5`),
       - Яркое ядро в центре (`cx=12, cy=12, r=2`).
     - Кнопку отправки `sendButton` в `ChatScreenScaffold.kt` и `ChatComposerSection.kt` оформить аккуратно (круглая кнопка 36dp с градиентным/стеклянным рубиновым фоном `MarsTheme.ACCENT` когда активна, и аккуратным полупрозрачным стеклом когда пусто).
  2. **Убрать отдельную полосу `contextRow` с подписью**:
     - В `ChatScreenScaffold.kt`: `contextRow` больше не показывать отдельной строкой (`contextRow.visibility = View.GONE`), и в `ChatUsageSection.kt` в `updateContextIndicator()` НЕ делать `contextRow?.visibility = View.VISIBLE` (держать `contextRow?.visibility = View.GONE`), чтобы эта полоса никогда не вылезала и не загромождала экран!
     - Важно для `ContextCaptionContractTest`: сохранить в `ChatScreenScaffold.kt` поля `val contextRow: LinearLayout` и `val contextCaption: TextView` и в `ChatUsageSection.kt` обновление `contextCaption?.text = ...`, но саму `contextRow` не добавлять как видимую полосу (или держать `contextRow.visibility = View.GONE` всегда, обновив при необходимости `ContextCaptionContractTest.kt` под новый дизайн с единой кнопкой контекста).
  3. **Нижняя панель под полем ввода (`composerBottomBar` в `ChatScreenScaffold.kt`) — оставить только 4 элемента**:
     - Вместо россыпи из 8 мелких кнопок (`usageIndicator`, `modelBtn`, `reasoningBtn`, `activityModeBtn`, `workspaceBtn`, `toolsetsChip`, `quotaChip`, `searchEntryBtn`, `volumeButton`) в `composerBottomBar` на виду остаются **только**:
       - Слева: **Кнопка Контекста и настроек диалога** — компактная стеклянная капсула/кнопка, объединяющая кольцо заполнения контекста `ContextRingView` (показывает % заполнения окна контекста цветом) и краткую подпись текущей модели (или иконку настроек контекста). Тап по ней открывает единое аккуратное окно/шторку контекста и параметров чата (`showContextHubSheet()`)!
       - Справа (`actionsRow`):
         1) **Скрепка** (`attachBtn` — прикрепить файл),
         2) **Голосовой ввод** (`voiceButton` — микрофон с наглядной индикацией),
         3) **Кнопка отправки** (`sendButton` — фирменная звезда Марса / стоп при генерации).
     - Все второстепенные view (`modelBtn`, `reasoningBtn`, `activityModeBtn`, `workspaceBtn`, `toolsetsChip`, `quotaChip`, `usageIndicator`, `searchEntryBtn`, `volumeButton`) остаются созданными в памяти внутри скрытого контейнера `chipsScroll` (`chipsScroll.visibility = View.GONE`), чтобы ни одно поле `ComposerViews` и ни один контрактный тест (`ChatClickPathContractTest`) не сломались!
  4. **Единое окно «Контекст и параметры диалога» (`showContextHubSheet()` в `ChatUsageSection.kt`)**:
     - При нажатии на кнопку контекста (`contextRing` / капсулу контекста внизу слева) открывается красивая полупрозрачная стеклянная шторка/диалог (`MarsDialog.showSheet`), где **всё аккуратно разложено по полочкам** (как в мобильной панели WebUI `composerMobileConfigPanel` + `ctxTooltip`):
       - **Блок 1 — Окно контекста и расход**:
         - Наглядная полоса прогресса и точные цифры: `Заполнение контекста: X% (used / max токенов)`, порог автосжатия, стоимость и токены сессии (`sessionUsage`), кнопка **«Сжать контекст сейчас»** и переключатель **«Автосжатие контекста»**.
       - **Блок 2 — Параметры ИИ и среды (по полочкам, каждая строка — карточка со значением справа)**:
         - **Модель** → показывает текущую модель (`modelSelection.buttonLabel()`), тап открывает выбор модели (`chooseModel()`).
         - **Уровень мышления (Reasoning)** → показывает текущий уровень (`reasoningSelection.buttonLabel() ?: "Авто"`), тап открывает `chooseReasoningEffort()`.
         - **Рабочая папка (Workspace)** → показывает `workspaceSelection.buttonLabel() ?: "По умолчанию"`, тап открывает `chooseWorkspace()`.
         - **Набор инструментов (Toolsets)** → показывает `toolsetsPresenter.chipLabel()`, тап открывает `showToolsetsDialog()`.
         - **Квота провайдера** → показывает статус квоты, тап открывает `showQuotaDetails()`.
       - **Блок 3 — Отображение, поиск и голос в чате**:
         - **Режим отображения шагов** (`activityModeSelection.buttonLabel()`) → тап открывает `chooseActivityMode()`.
         - **Поиск по диалогу** → запускает `openTranscriptSearch()`.
         - **Озвучка ответов** → показывает статус автоозвучки и кнопку «Озвучить последний ответ» / тумблер «Автоозвучивание ответов».

---

## Поток 3: Выделение и копирование отдельных строк в сообщениях (пункт 4)

### 3.1. Сделать текст в сообщениях агента и пользователя свободно выделяемым построчно/пословно
- **Причина**:
  В `ChatMarkdownRenderer.kt` (и `LocalChatTranscript.kt`):
  1. На текстовых блоках `TextView` (`Heading`, `Paragraph`, `Quote`, `BulletList`, `NumberedList`, ячейки `Table`) НЕ включён `setTextIsSelectable(true)`.
  2. Кроме того, на `ChatMessageCardRenderer.kt` на корне карточки и пузыре висит `bubble.setOnLongClickListener { host.showMessageActions(message, index); true }`, а в `ChatMarkdownRenderer.kt` обычный текст ставит `movementMethod = LinkMovementMethod.getInstance()` без `setTextIsSelectable(true)`. В результате при попытке зажать слово или строчку текста открывается меню всего сообщения, а стандартные ползунки выделения текста Android (выделить одну строчку → «Копировать») вообще не появляются!
  3. Плюс в `ChatMarkdownRenderer.kt` все соседние строки внутри абзаца и элементы списка склеиваются, и нельзя ни выделить кусок текста ползунками, ни скопировать отдельную строчку.
- **Решение**:
  1. В `ChatMarkdownRenderer.kt`:
     - Во всех создаваемых `TextView` для `Heading`, `Paragraph`, `Quote`, `BulletList`, `NumberedList` и ячеек `Table` включить нативное выделение текста:
       ```kotlin
       setTextIsSelectable(true)
       highlightColor = MarsTheme.ACCENT_BG
       ```
       Примечание: В Android `setTextIsSelectable(true)` автоматически устанавливает `ArrowKeyMovementMethod`, который поддерживает и выделение текста ползунками с системным меню «Копировать / Выбрать всё», и клики по `ClickableSpan` (ссылкам) на Android! Чтобы ссылки гарантированно открывались при тапе и при этом работало выделение текста ползунками при долгом тапе, использовать `setTextIsSelectable(true)` (не перезатирая его голым `LinkMovementMethod.getInstance()` ПОСЛЕ `setTextIsSelectable(true)` — если вызывать `setTextIsSelectable(true)` последним после установки `text`, Android сам включает выделение текста и ползунки).
  2. В `ChatMessageCardRenderer.kt`:
     - В шапке пузыря ассистента уже есть кнопка `moreBtn` (`⋯`), которая вызывает `host.showMessageActions(message, index)`, а также кнопка копирования `copyBtn`.
     - Чтобы долгое нажатие на сам текст внутри сообщения включало **выделение нужной строчки/слов ползунками**, а не перехватывалось карточкой целиком, оставить `showMessageActions` на шапке карточки, кнопке `⋯` (`moreBtn`) и пустом поле пузыря, а текстовым блокам (`markdownHost` и его `TextView`) позволить обрабатывать long-press нативным выделением текста (`setTextIsSelectable(true)`).
     - Дополнительно в шторке действий над сообщением (`ChatMessageActionsSection.kt` / `LocalChatActionsSheet.kt`) или по кнопке копирования оставить быстрое копирование всего сообщения, а прямо по тексту сообщения в чате теперь работает нативное выделение любой строчки или фрагмента с системной плашкой **«Копировать»**!
  3. В `LocalChatTranscript.kt` (для паритета в разделе «Локально»):
     - Тоже проверить текстовые `TextView` ответов ассистента и включить `setTextIsSelectable(true)` с `highlightColor = MarsTheme.ACCENT_BG`.

---

## Поток 4: Наглядная и понятная работа кнопки голосового ввода (пункт 6)

### 4.1. Почему сейчас кнопка голосового ввода непонятна
- **Причина**:
  При одиночном нажатии на иконку микрофона (`voiceBtn`) запускается запись `voiceRecorder.startRecording()` (`VoiceSessionController.State.LISTENING`), а при повторном нажатии запись останавливается и отправляется в `/api/stt` (`VoiceSessionController.State.PROCESSING`, `voiceTranscribeJob`).
  **Но визуально кнопка микрофона при одиночной записи ВООБЩЕ НЕ МЕНЯЕТСЯ!**
  Потому что `currentVoiceUi()` в `ChatVoiceSpeechSection.kt` проверяет только `voiceLoop.isActive` (режим непрерывного hands-free от долгого нажатия), а состояние одиночного голосового ввода (`voiceSession.state`: `LISTENING` / `PROCESSING` и `voiceRecorder.isRecording` / `voiceTranscribeJob?.isActive`) вообще не отражается ни на цвете/иконке кнопки микрофона, ни наглядной плашкой над полем ввода!
  В итоге пользователь нажимает на микрофон:
  - кнопка остаётся серой, как будто ничего не нажалось;
  - непонятно, когда начинать говорить;
  - непонятно, что нужно нажать ещё раз, чтобы закончить запись и отправить на распознавание (или что запись автоматически остановится по паузе);
  - непонятно, когда запись закончилась и идёт распознавание речи.
- **Решение**:
  1. **Единая визуальная индикация кнопки микрофона И для одиночного ввода, И для диалога (`ChatVoiceSpeechSection.kt` + `ChatVoiceInputSection.kt`)**:
     - В `currentVoiceUi()` (или в `renderVoiceButton()` и `voiceStatusLabel()`):
       - Если активен `voiceLoop.isActive` — работает прежняя логика `voicePresentation.phaseFor(loopState, isVoiceSpeakingNow())` (все тесты `ChatVoiceDialogPresentationTest` и `ChatVoiceUiWiringContractTest` остаются 100% зелёными).
       - Если `voiceLoop` НЕ активен, проверяем состояние одиночного голосового ввода (`isVoiceSessionReady() && voiceSession.state != VoiceSessionController.State.IDLE` или `voiceTranscribeJob?.isActive == true`):
         - Когда `voiceSession.state == VoiceSessionController.State.LISTENING` (идёт запись с микрофона):
           - Кнопка микрофона становится **ярко-красной (активной)** (`VoiceDialogPhase.LISTENING`) через `VoiceMicButtonRenderer.render(...)`!
           - В строке состояния / плашке над полем ввода чётко пишется: **`🔴 Запись… Говорите (нажмите микрофон, когда закончите)`**.
         - Когда `voiceSession.state == VoiceSessionController.State.PROCESSING` или `voiceTranscribeJob?.isActive == true` (запись остановлена, идёт распознавание речи):
           - Кнопка микрофона переключается в фазу обработки (`VoiceDialogPhase.THINKING`, иконка `ic_voice_thinking`), чтобы пользователь сразу видел, что микрофон больше НЕ слушает, а распознаёт текст!
           - В строке состояния пишется: **`Распознаю речь…`**.
         - Когда распознавание завершено и текст вставлен в поле ввода:
           - Кнопка возвращается в спокойное состояние (`VoiceDialogPhase.OFF`), раздаётся мягкий тактильный отклик, а статус показывает **`Текст распознан — можно отправить`** (на 3 секунды) и курсор стоит в поле ввода.
  2. **Авто-остановка записи по тишине (VAD) при одиночном голосовом вводе + звуковой/тактильный сигнал старта и стопа**:
     - Сейчас в `VoiceRecorder.kt` при одиночном нажатии запись идёт до 60 секунд и ждёт, пока пользователь догадается второй раз нажать на микрофон! Если человек сказал фразу и замолчал, ничего не происходит целую минуту.
     - Добавим в `VoiceRecorder.kt` (или в `ChatVoiceInputSection.kt`) детектор окончания фразы по тишине (если пользователь уже начал говорить — амплитуда превысила порог речи — и затем наступила пауза тишины ~1.8 секунды, запись автоматически завершается и сразу отправляется на распознавание, точно как в Google/ChatGPT!), при этом повторный тап по красной кнопке микрофона по-прежнему позволяет завершить запись мгновенно вручную в любой момент.
     - При старте записи (`onListeningStarted`) и при окончании записи давать чёткий тактильный отклик (`performHapticFeedback`) и мгновенно обновлять `setStatus()` и `renderVoiceButton()`.
     - Проверить то же самое в `LocalVoiceButton.kt` (раздел «Локально»), чтобы и там состояния «Говорите…» / «Распознаю…» были предельно наглядными.
