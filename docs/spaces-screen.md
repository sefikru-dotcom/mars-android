# Экран Spaces/Workspaces (app-mobile) — документ для интегратора

Мобильный экран «Пространства»: каталог `GET /api/workspaces` со строкой
«Авто» (серверный workspace по умолчанию, поле `last`) и явным выбором
активного пространства. Выбор переживает перезапуск и сразу влияет на
исходящие сообщения чата (`[Workspace::v1: <path>]`-сентинел).

## Запуск из меню (для писателя ChatActivity/SidebarNavPolicy)

Экран намеренно не подключён к боковому меню: `ChatActivity` и
`SidebarNavPolicy` в этой волне заморожены. Публичная точка входа:

```kotlin
startActivity(SpacesActivity.newIntent(this))   // ru.mars.mobile.SpacesActivity
```

- Activity объявлена в `app-mobile/src/main/AndroidManifest.xml`
  (`.SpacesActivity`, не exported — запуск только изнутри приложения).
- Кнопка «Назад» и системный back — `finish()`, возврат в вызвавший
  `ChatActivity`. Пересоздавать сессию/стрим не нужно.
- Рекомендуемое место в меню: секция `SidebarSection(id = "spaces",
  label = "Пространства", iconKey = "ic_nav_spaces", target = NavTarget.SPACES)`
  (иконка-слои `res/drawable/ic_nav_spaces.xml`, stroke 1.7 — стиль
  остальных `ic_nav_*`). Порядок в `SidebarNavPolicy.sections` контрактный,
  поэтому добавление — за волной, владеющей policy.
- После выбора в ChatActivity достаточно перечитать chip:
  `workspaceSelection.resolveEffective(...)`/`buttonLabel()` — storage общий
  (`mars-workspace-choice`, `PrefsWorkspaceChoiceStorage`).

## Слои

- `SpacesActivity` — UI: header (назад/заголовок/прогресс/refresh),
  notice-плашка, список карточек, статус-блок с «Повторить». Safe-area через
  WindowInsets; длинный путь усекается (без горизонтального overflow).
- `SpacesPresenter` — состояния LOADING/READY/EMPTY/ERROR/NO_AUTH,
  offline-safe: при недоступном каталоге показывает сохранённый выбор.
  Режим «Авто» отслеживается сессионно в презентере: контракт
  `WorkspaceSelection.select(null)` при известном каталоге персистит
  принятый серверный default (как чип ChatActivity), поэтому «нет явного
  пика» нельзя вывести из storage после нажатия «Авто».
- `SpacesPolicy` — чистая JVM-политика: строки, тексты, метки «Активно»/
  «По умолчанию», ключ «Авто» (`__auto__`). Unit-тесты:
  `SpacesPolicyTest`, `SpacesPresenterTest`.
- Сеть — существующий `WorkspaceRepository` (core-network, без правок).

## Motion

Reveal списка — fade + slide-up через `MotionPolicy.durationFor(MESSAGE_REVEAL,
reduced)`; при системном «уменьшить движение» (ANIMATOR_DURATION_SCALE = 0)
длительность 0 и анимация не запускается. Кривая — decelerate (без bounce).
