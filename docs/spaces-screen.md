# Экран Spaces/Workspaces (app-mobile) — документ для интегратора

Мобильный экран «Пространства»: каталог `GET /api/workspaces` со строкой
«Авто» (серверный workspace по умолчанию, поле `last`) и явным выбором
активного пространства. Выбор переживает перезапуск и сразу влияет на
исходящие сообщения чата (`[Workspace::v1: <path>]`-сентинел).

## Запуск из меню

Экран подключён к боковому меню (волна `wave/android-spaces-nav`): секция
`SidebarSection(id = "spaces", label = "Пространства", iconKey = "ic_nav_spaces",
target = NavTarget.SPACES)` — между «Скиллы» и «Настройки» (settings всегда
последний; контрактный порядок остальных разделов не менялся). Сопоставление
`NavTarget.SPACES -> SpacesActivity.newIntent(this)` живёт в
`ChatActivity.buildSidebarNav()`.

- Activity объявлена в `app-mobile/src/main/AndroidManifest.xml`
  (`.SpacesActivity`, не exported — запуск только изнутри приложения).
- Кнопка «Назад» и системный back — `finish()`, возврат в вызвавший
  `ChatActivity`. Пересоздавать сессию/стрим не нужно.
- Иконка-слои `res/drawable/ic_nav_spaces.xml`, stroke 1.7 — стиль
  остальных `ic_nav_*`.
- После выбора ChatActivity перечитывает chip в `onResume`:
  `workspaceSelection.reloadFromStorage()` + `updateWorkspaceButton()` —
  storage общий (`mars-workspace-choice`, `PrefsWorkspaceChoiceStorage`),
  а `WorkspaceSelection.current` кэшируется при создании экземпляра, поэтому
  без перечитывания chip остался бы устаревшим.

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
