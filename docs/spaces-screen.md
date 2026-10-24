# Экран «Пространства» (app-mobile) — документ для интегратора

Мобильный экран каталога рабочих пространств: `GET /api/workspaces`
(`WorkspaceRepository`), строка «Авто» (маршрут выбирает сервер) и явный выбор
пространства, в котором работают следующие сообщения. Выбор переживает
перезапуск и сразу влияет на исходящий текст чата (`[Workspace::v1: <path>]`).

## Запуск из меню

Экран — раздел левого бара `AppDestination.SPACES` (предпоследний, перед
«Настройками»): клик по строке бара идёт через
`AppNavIntents.sectionIntent` — единственную таблицу «раздел → Activity».

```kotlin
startActivity(AppNavIntents.sectionIntent(this, AppDestination.SPACES))
// либо напрямую: Intent(this, SpacesActivity::class.java)
```

- Activity объявлена в `app-mobile/src/main/AndroidManifest.xml`
  (`.SpacesActivity`, не exported — запуск только изнутри приложения).
- Кнопка «Назад» и системный back — `finish()`, возврат в вызвавший экран;
  пересоздавать сессию/стрим не нужно.
- Иконка раздела — `ic_nav_spaces` (`res/drawable/ic_nav_spaces.xml`, stroke 1.7 —
  стиль остальных `ic_nav_*`), состав и порядок бара — контракт
  `AppSectionsPolicy`.

## Левый бар, а не свой тулбар

Отдельного тулбара/шторки у экрана нет: левый бар — компонент навигатора
(`AppSectionsBar` в `ChatActivity`/`ChatSessionsActivity`), а раздел открывается
маршрутом `AppNavIntents.sectionIntent`. Своя шапка экрана — общий
`SectionScaffold` (назад, заголовок, прогресс, «Обновить»), то есть тот же
минималистичный каркас, что у «Проектов», «Памяти» и «Скиллов»: крупных кнопок
на экране нет вообще, выбор делается тапом по строке каталога.

## Слои

- `SpacesActivity` — только разметка: `SectionScaffold`, live-строка счётчика,
  карточки строк, статус-блок с «Повторить», safe-area из shell, палитра
  `MarsTheme`. Длинные имена и пути усекаются в одну строку, горизонтального
  скролла нет.
- `SpacesPresenter` — состояния LOADING/READY/EMPTY/ERROR/NO_AUTH, шов
  `SpacesGateway` (на устройстве `RepositorySpacesGateway`, в тестах fake без
  HTTP), выбор через `WorkspaceSelection`, offline-safe поведение: при отказе
  сети свежий каталог сессии показывается с плашкой кэша, иначе — сохранённый
  явный выбор отдельной строкой.
- `SpacesPolicy` — чистая JVM-политика: строки каталога, метки «Активно» и
  «По умолчанию», тексты фаз, `pathLabel`, возраст кэша.

## Общее хранилище выбора

`WorkspaceSelection` пишет в префс `mars-workspace-choice`
(`WORKSPACE_CHOICE_PREFS`) — тот же, что читает чип композера чата. При
возврате в чат `ChatActivity.resumeChat()` вызывает
`WorkspaceSelection.reloadFromStorage()` и обновляет подпись чипа, поэтому
выбор с этого экрана виден без перезапуска и без повторной загрузки каталога.

## Мутации

Экран не меняет состояние сервера: маршрут `GET /api/workspaces` входит в
allowlist нативного device-токена, мутации (`/api/workspaces/add` и пр.) — нет.
«Выбор» живёт целиком на клиенте и уходит в сообщение сентинелом.

## Motion

Reveal каталога — fade + slide-up через
`MotionPolicy.durationFor(MotionCue.MESSAGE_REVEAL, reduced)`; при системном
«уменьшить движение» (`ANIMATOR_DURATION_SCALE = 0`) длительность 0 и анимация
не запускается. Кривая — decelerate, без bounce.
