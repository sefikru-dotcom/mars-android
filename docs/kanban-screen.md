# Read-only экран «Канбан» (app-mobile) — документ для интегратора

Мобильная витрина kanban-доски: `GET /api/kanban/board` (колонки/задачи/
статусы), `GET /api/kanban/stats` (сводка), `GET /api/kanban/config`
(порядок/скелет lane-колонок и `include_archived_by_default`) и
`GET /api/kanban/tasks/{id}` (read-only диалог карточки). **Строго
read-only**: в экране, презентере и policy нет и не должно появиться
dispatch/create/edit/move/delete — серверный Bearer allowlist допускает
только GET/HEAD `/api/kanban/`.

## Запуск из меню

Экран подключён к левому бару: раздел `AppDestination.KANBAN` запускается
через `AppNavIntents.sectionIntent` (единственная таблица «раздел → Activity»).
Публичная точка входа:

```kotlin
startActivity(KanbanActivity.newIntent(this))   // ru.mars.mobile.KanbanActivity
```

- Activity объявлена в `app-mobile/src/main/AndroidManifest.xml`
  (`.KanbanActivity`, не exported — запуск только изнутри приложения).
- Кнопка «Назад» и системный back — `finish()`, возврат в вызвавший экран.
- Иконка раздела — `ic_nav_kanban` (`res/drawable/ic_nav_kanban.xml`,
  stroke 1.7 — стиль остальных `ic_nav_*`), состав и порядок бара —
  контракт `AppSectionsPolicy`.

## Слои

- `KanbanActivity` — UI: header (назад/заголовок/прогресс/refresh),
  бейдж «доска только для чтения», notice-плашка, сводка статистики
  (summary + чипы by_status), горизонтальный свайп колонок с независимым
  вертикальным скроллом карточек, статус-блок с «Повторить». Тап по
  карточке — read-only диалог (`MarsDialog`): тело, факты, комментарии,
  связи (реальные id родительских/дочерних задач пилюлями; тап по id
  открывает detail связанной задачи — только GET), хвост ленты событий;
  единственное действие — «Закрыть».
- `KanbanPresenter` — состояния LOADING/READY/EMPTY/ERROR/NO_AUTH,
  offline-safe кэш (отказ сети при живом кэше -> ERROR со stale-колонками
  и notice, экран не пустует), best-effort статистика (отказ stats не топит
  доску) и best-effort конфиг `GET /api/kanban/config` (отказ — серверный
  порядок колонок, архив скрыт). Конфиг перечитывается полной загрузкой и
  переключением доски/исполнителя; polling берёт кэш.
  `include_archived_by_default:true` прокидывается флагом `includeArchived`
  в каждый board-запрос. Polling-контракт: `refresh()` ходит с
  `since=<latest_event_id>`; короткий конверт `changed:false` НИКОГДА не
  рендерится пустой доской и не публикует LOADING — тихо обновляется только
  курсор. Дефектный `changed:false` без кэша деградирует в EMPTY (не
  crash/ERROR). Авто-polling каждые 30 c пока экран resumed
  (`POLL_INTERVAL_MS`).
- `KanbanPolicy` — чистая JVM-политика: статус→тон (ready/running/blocked/
  done/neutral), возрастные метки, подписи исполнителя/приоритета, чипы
  статистики в каноническом порядке, строки detail-диалога (только реально
  присутствующие поля), `columnsFor(board, configColumns)` — порядок и
  скелет колонок по конфигу (WebUI-паритет: неизвестные lane, включая
  архивные, — хвостом; доска без колонок получает пустой скелет из
  конфига). Unit-тесты: `KanbanPolicyTest`, `KanbanPresenterTest`.
- Сеть — `KanbanRepository` (core-network): `getBoard`/`getStats`/
  `getConfig`/`getTaskDetail`/`getBoards`/`getAssignees`/`streamEvents`;
  `dispatch` не вызывается ниоткуда в app-mobile (контракт-тест
  `KanbanShellContractTest` фиксирует allowlist GET-вызовов экрана).

## Motion

Reveal колонок — fade + slide-up через `MotionPolicy.durationFor(
MESSAGE_REVEAL, reduced)`; при системном «уменьшить движение»
(ANIMATOR_DURATION_SCALE = 0) длительность 0 и анимация не запускается.
Кривая — decelerate (без bounce).
