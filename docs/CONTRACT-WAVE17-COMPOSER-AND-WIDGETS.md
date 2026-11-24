# Контракт Волны 17: Доводка композера (звезда в тёмном ореоле, единый размер кнопок 36dp, адаптация поля ввода) и Виджеты рабочего стола Android

Ветка: `feat/local-chat-wave` (`C:/Users/SKY/.zcode/workspace/default/mars-android`).
Важно: **Воркерам запрещено запускать `./gradlew` / `bash build-mars.sh`** — компиляцию, прогон тестов и сборку APK делает только координатор!

---

## Поток 1: Звезда отправки в тёмном ореоле + Единый размер и тональность кнопок композера (пункты 1 и 2 владельца)

### Файлы в зоне ответственности Потока 1:
- `app-mobile/src/main/java/ru/mars/mobile/MarsTheme.kt`
- `app-mobile/src/main/java/ru/mars/mobile/ChatScreenScaffold.kt`
- `app-mobile/src/main/java/ru/mars/mobile/ChatComposerSection.kt`
- `app-mobile/src/main/java/ru/mars/mobile/VoiceMicButtonRenderer.kt`
- `app-mobile/src/main/java/ru/mars/mobile/LocalChatComposerBar.kt`
- `app-mobile/src/main/java/ru/mars/mobile/LocalVoiceButton.kt`
- `app-mobile/src/main/res/drawable/ic_send.xml`

### Технические требования Потока 1:
1. **Звезда отправки в тёмном ореоле (не в сплошном ярко-красном круге!) — паритет с WebUI (`style.css:9323–9405`)**:
   - В WebUI кнопка отправки `.send-btn` имеет **тёмный стеклянный ореол** (`background: rgba(18, 22, 32, 0.65)` в покое и `rgba(26, 16, 22, 0.82)` с деликатной рубиновой рамкой `rgba(239, 68, 68, 0.45)` в активном состоянии), а не залитый ярко-красный круг `MarsTheme.ACCENT`!
   - Добавить в `MarsTheme.kt`:
     - Константы для тёмного ореола кнопок композера:
       - `const val COMPOSER_ACTION_BG = 0xB8121620.toInt()` — глубокий тёмно-кобальтовый полупрозрачный ореол для всех 3 кнопок композера (скрепка, микрофон, отправка в покое);
       - `const val COMPOSER_ACTION_BORDER = 0x26FFFFFF` — тонкая деликатная стеклянная грань (1dp);
       - `const val SEND_HALO_BG = 0xD81A1018.toInt()` — тёмный рубиново-космический ореол звезды отправки;
       - `const val SEND_HALO_BORDER = 0x73EF4444` — благородное тёмно-рубиновое кольцо ореола звезды;
       - `const val SEND_STAR_TINT = 0xFFFCA5A5.toInt()` — мягкое тёплое свечение самой звезды внутри тёмного ореола (или `MarsTheme.TEXT_STRONG` / встроенная палитра `ic_send.xml`).
     - Фабрику `fun sendHaloBackground(context: Context, active: Boolean = true, stopping: Boolean = false): RippleDrawable` (или настройку в `circleIconButton`), создающую овальный тёмный ореол (`SEND_HALO_BG` с тонким кольцом `SEND_HALO_BORDER` 1dp, а в режиме `stopping` — `0xD82A1215.toInt()` с кольцом `MarsTheme.ACCENT`).
   - В `ic_send.xml`:
     - Добавить мягкое тёмно-рубиновое внутреннее свечение ореола вокруг звезды (радиальный/многослойный полупрозрачный ореол `r=10` с `#1AEF4444` и `#2EE11D48`), изящную орбиту, четырёхлучевую звезду и яркое ядро, чтобы в тёмном круге кнопка выглядела как настоящая звезда в тёмном ореоле, а не плоская иконка на красной бляшке.
2. **Единый размер (36dp) и единая тональность всех 3 кнопок справа (`attachBtn`, `voiceButton`, `sendButton`)**:
   - В `ChatScreenScaffold.kt`:
     - Задать всем трём кнопкам в `actionsRow` **строго одинаковый размер `36dp x 36dp`** (`sizeDp = 36`, `LinearLayout.LayoutParams(dp(36), dp(36))`) и **равномерный шаг `marginStart = dp(6)`** между ними, а также `gravity = Gravity.CENTER_VERTICAL`!
     - `attachBtn`: `sizeDp = 36`, `bg = MarsTheme.COMPOSER_ACTION_BG`, `strokeColor = MarsTheme.COMPOSER_ACTION_BORDER`, `iconTint = MarsTheme.TEXT_EM`.
     - `voiceButton`: `sizeDp = 36`, `bg = MarsTheme.COMPOSER_ACTION_BG`, `strokeColor = MarsTheme.COMPOSER_ACTION_BORDER`, `iconTint = MarsTheme.TEXT_EM`. В `VoiceMicButtonRenderer.kt` в неактивной фазе использовать `setColor(MarsTheme.COMPOSER_ACTION_BG)` и `setStroke(dp(INACTIVE_STROKE_DP), MarsTheme.COMPOSER_ACTION_BORDER)`, а иконку в неактивной фазе красить в `MarsTheme.TEXT_EM` (чтобы скрепка и микрофон были в абсолютно идентичных благородных тёмных тонах!).
     - `sendButton`: `sizeDp = 36`, `bg = MarsTheme.SEND_HALO_BG`, `strokeColor = MarsTheme.SEND_HALO_BORDER`, `iconTint = MarsTheme.SEND_STAR_TINT` (или `#FFF8FAFC`).
   - В `ChatComposerSection.kt` (в `updateControls()`):
     - Сохранить обязательные строки для `ChatAccessibilityContractTest`:
       `sendButton.setImageDrawable(ContextCompat.getDrawable(this, if (uiState.typing) R.drawable.ic_stop else R.drawable.ic_send))`
       `sendButton.imageTintList = ColorStateList.valueOf(MarsTheme.TEXT_STRONG)`
       `sendButton.contentDescription = ChatA11yPolicy.sendButtonDescription(uiState.typing)`
     - Обновлять фон `sendButton.background = MarsTheme.sendHaloBackground(this, active = true, stopping = uiState.typing)` — чтобы звезда ВСЕГДА оставалась в тёмном ореоле, а не заливалась ярко-красным кругом!
   - В `LocalChatComposerBar.kt` и `LocalVoiceButton.kt` (для паритета в разделе «Локально»):
     - Сделать `attachButton`, `voice` (`SIZE_DP = 36`) и `sendButton` также единого размера **36dp** в единых тёмных тонах (`COMPOSER_ACTION_BG` / `SEND_HALO_BG` с `SEND_HALO_BORDER`), с ровными отступами.

---

## Поток 2: Идеальная адаптация и геометрия окна ввода текста (пункт 3 владельца)

### Файлы в зоне ответственности Потока 2:
- `app-mobile/src/main/java/ru/mars/mobile/ChatScreenScaffold.kt` (блок капсулы `composer` и `input`)
- `app-mobile/src/main/java/ru/mars/mobile/ComposerInputTypePolicy.kt`
- `app-mobile/src/test/java/ru/mars/mobile/ComposerInputTypePolicyTest.kt`

### Диагноз проблемы с полем ввода текста:
- Сейчас в `ChatScreenScaffold.kt` (строки 751–845):
  1) У `composer` стоял тесный `setPadding(dp(10), dp(6), dp(10), dp(6))`, а у `input` — `setPadding(dp(6), dp(4), dp(6), dp(4))` и `includeFontPadding = false`.
  2) В `MarsTheme.composerCapsule(context)` радиус скругления капсулы равен **22dp** (`cornerRadius = dp(22)`). При радиусе скругления углов 22dp и маленьком отступе (`10 + 6 = 16dp` по горизонтали, `6 + 4 = 10dp` по вертикали) первые и последние символы строки, а также верхние/нижние выносные элементы букв (`Й`, `Щ`, `р`, `у`, `д`) и курсор физически **наезжают на скруглённую рамку капсулы ввода и выходят за её внутренний контур**!
  3) Кроме того, при `includeFontPadding = false` и ручном расчёте `maxHeight` через `paint.fontMetricsInt.let { it.descent - it.ascent }` на первой строке высокие глифы и курсор подрезаются сверху и снизу.

### Технические требования Потока 2:
1. В `ChatScreenScaffold.kt`:
   - Сделать для текстового поля внутри капсулы композера выделенную равномерную внутреннюю область (или настроить отступы `composer` и `input` так, чтобы текст никогда не приближался к скруглённым бортам капсулы):
     - У `composer`: `setPadding(dp(12), dp(10), dp(12), dp(8))` и `clipToPadding = false`.
     - Для самого поля `input` (`EditText`):
       - обернуть или оформить `input` с идеальными симметричными внутренними отступами `setPadding(dp(12), dp(10), dp(12), dp(10))`, тонкой внутренней подложкой `MarsTheme.card(context, Color.argb(95, 10, 14, 22), radiusDp = 14, strokeColor = MarsTheme.BORDER_SUBTLE)` (или аккуратным внутренним контуром поля ввода с `radiusDp = 14`), `gravity = Gravity.TOP or Gravity.START`, `minHeight = dp(42)`!
       - Когда у поля ввода есть собственные скруглённые границы (`radiusDp = 14`) и симметричный внутренний отступ `12dp` слева/справа и `10dp` сверху/снизу, ни один символ и ни один курсор при вводе 1–5 строк никогда не вылезет за рамку плашки ввода текста!
       - Включить `includeFontPadding = true` (или сохранить строку `includeFontPadding = ...` с достаточным `verticalPaddingPx = dp(10) + dp(10)` — проверить `ComposerInputTypePolicyTest.kt`, чтобы все контрактные проверки в `ComposerInputTypePolicyTest` проходили на 100%!).
       - Добавить `setLineSpacing(dp(2).toFloat(), 1.05f)` для ровного межстрочного ритма при многострочном наборе.
       - Отступ между полем ввода `input` и нижней панелью кнопок `composerBottomBar` сделать ровным (`topMargin = dp(6)` или `setPadding(0, dp(6), 0, 0)`).

---

## Поток 3: Виджеты рабочего стола Android для быстрого доступа и минимальной работы с домашнего экрана (пункт 4 владельца)

### Файлы в зоне ответственности Потока 3:
- `app-mobile/src/main/java/ru/mars/mobile/widget/MarsQuickWidgetProvider.kt` (НОВЫЙ)
- `app-mobile/src/main/java/ru/mars/mobile/widget/MarsWidgetActionsPolicy.kt` (НОВЫЙ — чистая JVM-политика маршрутизации и состояния виджета)
- `app-mobile/src/main/res/layout/widget_mars_quick.xml` (НОВЫЙ — RemoteViews-разметка виджета 4x2 / масштабируемая от 3x1 до 4x2)
- `app-mobile/src/main/res/drawable/widget_mars_bg.xml` (НОВЫЙ — глубокий космический полупрозрачный фон виджета с рубиновой гранью Марса)
- `app-mobile/src/main/res/drawable/widget_mars_input_pill.xml` (НОВЫЙ — капсула быстрого ввода / поиска в виджете)
- `app-mobile/src/main/res/drawable/widget_mars_action_btn.xml` (НОВЫЙ — тёмные круглые кнопки действий в ореоле для виджета)
- `app-mobile/src/main/res/drawable/widget_mars_star_btn.xml` (НОВЫЙ — тёмный ореол с рубиновым кольцом для кнопки звезды в виджете)
- `app-mobile/src/main/res/xml/mars_quick_widget_info.xml` (НОВЫЙ — `appwidget-provider`)
- `app-mobile/src/main/res/values/strings.xml` (добавить строки описания виджета)
- `app-mobile/src/main/AndroidManifest.xml` (зарегистрировать `<receiver android:name=".widget.MarsQuickWidgetProvider" android:exported="true">` с `ACTION_APPWIDGET_UPDATE`)
- `app-mobile/src/main/java/ru/mars/mobile/ChatInitSection.kt` и `ChatActivity.kt` (обработка `EXTRA_WIDGET_ACTION`: автостарт голосового ввода `voice` или фокуса клавиатуры `compose` / открытия последнего диалога прямо с рабочего стола, а также обновление виджета при смене активной сессии/статуса)
- `app-mobile/src/test/java/ru/mars/mobile/MarsWidgetActionsPolicyTest.kt` (НОВЫЙ — unit-тесты виджета и его контракта в манифесте)

### Что должен уметь виджет Марса на рабочем столе телефона:
1. **Дизайн в едином космическом стиле Марса (`widget_mars_quick.xml`)**:
   - Тёмная космическая карточка (`#E60B0E17` с угловым радиусом `22dp` и тонкой рубиновой рамкой `#4DEF4444`).
   - **Верхняя строка виджета**:
     - Иконка планеты Марс (`@drawable/ic_mars`) + заголовок **«МАРС»** и индикатор/название последнего активного диалога или модели (читается из `SharedPreferences` `mars_widget_state`: название последней сессии, превью последнего ответа агента в 1–2 строки и время). Тап по карточке последнего диалога сразу открывает этот диалог в `ChatActivity` (`EXTRA_OPEN_SESSION_ID`)!
     - Кнопка **«Диалоги»** (тап открывает `ChatSessionsActivity` — список всех диалогов).
   - **Нижняя панель быстрых действий (минимальная работа прямо с рабочего стола в 1 тап)**:
     - **Капсула «Спросить Марс…»** (тап сразу открывает `ChatActivity` с новым или текущим чатом и мгновенно поднимает клавиатуру в поле ввода текста `EXTRA_WIDGET_ACTION = "compose"`).
     - **Круглая кнопка «Голос» (`@drawable/ic_mic`)** в тёмном ореоле (тап открывает `ChatActivity` и автоматически запускает голосовой ввод `EXTRA_WIDGET_ACTION = "voice"`, чтобы сказать фразу в один тап с рабочего стола!).
     - **Круглая кнопка «Голосовой режим» / «Локально» (`@drawable/ic_nav_local`)** в тёмном ореоле (тап сразу открывает `VoiceModeActivity` или `LocalChatActivity`).
     - **Круглая кнопка со звездой (`@drawable/ic_send`)** в тёмном рубиновом ореоле (быстрый старт нового чата `EXTRA_WIDGET_ACTION = "new_chat"`).
2. **Живое обновление виджета (`MarsQuickWidgetProvider.updateAllWidgets(context, sessionId, sessionTitle, lastSnippet)`)**:
   - Когда в `ChatActivity` открывается диалог или приходит ответ от агента, сохранять `sessionTitle`, `sessionId` и краткое превью ответа в `mars_widget_state` и вызывать `MarsQuickWidgetProvider.notifyStateChanged(this)`, чтобы на рабочем столе телефона всегда отображался актуальный последний диалог и его статус!
