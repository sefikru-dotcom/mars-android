# Экран «Канбан» (app-mobile) — документ для интегратора

Мобильная доска: чтение — `GET /api/kanban/board` (колонки/задачи/статусы),
`GET /api/kanban/stats` (сводка), `GET /api/kanban/config` (порядок/скелет
lane-колонок и `include_archived_by_default`), `GET /api/kanban/tasks/{id}`
(диалог карточки), `GET /api/kanban/boards`, `GET /api/kanban/assignees`,
`GET /api/kanban/events/stream` (SSE). Запись — ровно те маршруты, которые
`api/auth.py` допускает для device-Bearer (`NATIVE_BEARER_KANBAN_WRITE_PATHS` +
`NATIVE_BEARER_KANBAN_TASK_WRITE_SUFFIXES`), каждый связан с одним глаголом:

| Действие | Маршрут | Тело |
|---|---|---|
| Создать карточку | `POST /api/kanban/tasks?board=` | `{"title","body?","assignee?","priority?","tenant?"}` |
| Изменить/переместить | `PATCH /api/kanban/tasks/{id}/patch?board=` | только переданные поля (`status`, `title`, `assignee`, …) |
| Комментарий | `POST /api/kanban/tasks/{id}/comments?board=` | `{"body","author":"android"}` |
| Блокировать / разблокировать | `POST /api/kanban/tasks/{id}/block` / `/unblock` | `{"reason"?}` / `{}` |
| Массовая операция | `POST /api/kanban/tasks/bulk?board=` | `{"ids":[…],"status"?,"archive"?,"assignee"?,"priority"?}` |
| Связь родитель → потомок | `POST /api/kanban/links` (снятие — `/links/delete`) | `{"parent_id","child_id"}` |

Управление досками (create/patch/delete/switch), `dispatch` и `PATCH /config`
экрану недоступны — их нет в allowlist. `status:"running"` сервер отклоняет
(состояние claim-протокола диспетчера), поэтому пикер перемещения его не
предлагает: карточка двигается через `ready`/`blocked`/`done`/архив.

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

- `KanbanActivity` — UI: header (назад/заголовок/прогресс/refresh), бейдж
  «доска только для чтения» (только при серверном `read_only`), notice-плашка,
  панель записи («Новая задача», «Выбрать»), панель массовых действий,
  сводка статистики, горизонтальный свайп колонок с независимым вертикальным
  скроллом карточек, статус-блок с «Повторить». Тап по карточке — диалог
  (`MarsDialog`): тело, факты, комментарии, связи (тап по id открывает
  связанную задачу), хвост ленты событий и действия записи — «Переместить»
  (пикер колонок доски), «Комментарий», «Блокировать»/«Разблокировать»,
  «Связать». Долгое нажатие на карточке включает режим выбора: тап
  переключает выбор, панель даёт «Переместить» и «В архив» (одна bulk-мутация
  на все выбранные id). Панель записи и действия диалога скрыты, когда сервер
  объявил доску `read_only` (старый мост без маршрутов записи).
- `KanbanPresenter` — состояния LOADING/READY/EMPTY/ERROR/NO_AUTH,
  offline-safe кэш (отказ сети при живом кэше -> ERROR со stale-колонками
  и notice, экран не пустует), best-effort статистика (отказ stats не топит
  доску) и best-effort конфиг (отказ — серверный порядок колонок, архив
  скрыт). Конфиг перечитывается полной загрузкой и переключением
  доски/исполнителя; polling берёт кэш.
  `include_archived_by_default:true` прокидывается флагом `includeArchived`
  в каждый board-запрос. Polling-контракт: `refresh()` ходит с
  `since=<latest_event_id>`; короткий конверт `changed:false` НИКОГДА не
  рендерится пустой доской и не публикует LOADING — тихо обновляется только
  курсор. Дефектный `changed:false` без кэша деградирует в EMPTY (не
  crash/ERROR). Авто-polling каждые 30 c пока экран resumed
  (`POLL_INTERVAL_MS`).
  Запись: `createTask`/`moveTask`/`comment`/`setBlocked`/`linkTasks`/
  `archiveTasks`/`moveTasks` через порт `KanbanWritePort`; каждая успешная
  мутация завершается `refresh()` — на экране серверная правда, а не догадка.
  Локальные проверки (пустое название/комментарий/связь, пустой выбор) не
  тратят round-trip; отказы приходят готовой строкой `KanbanActionOutcome`.
- `KanbanPolicy` — чистая JVM-политика: статус→тон (ready/running/blocked/
  done/neutral), возрастные метки, подписи исполнителя/приоритета, чипы
  статистики в каноническом порядке, строки detail-диалога (только реально
  присутствующие поля), `columnsFor(board, configColumns)` — порядок и
  скелет колонок по конфигу (WebUI-паритет: неизвестные lane, включая
  архивные, — хвостом), `moveTargets(columns)` — цели пикера перемещения из
  фактических lane-статусов доски, `selectionLabel`/`bulkDoneLabel` — подписи
  массовых действий. Unit-тесты: `KanbanPolicyTest`, `KanbanPolicyWritesTest`,
  `KanbanPresenterTest`, `KanbanPresenterWritesTest`.
- Сеть — `KanbanRepository` (core-network): `getBoard`/`getStats`/
  `getConfig`/`getTaskDetail`/`getBoards`/`getAssignees`/`streamEvents` +
  запись `createTask`/`patchTask`/`addComment`/`setBlocked`/`bulkUpdate`/
  `linkTasks`. Тела собирает `KanbanRequestBodies` (строгий RFC 8259 через
  `JsonStringEncoding`), результат — типизированные `KanbanTaskWriteResult`/
  `KanbanCommentResult`/`KanbanBulkResult`/`KanbanLinkResult` (404 → `NotFound`,
  400/409/503 → `Failed` с текстом бриджа). Контракт-тест
  `KanbanShellContractTest` фиксирует allowlist вызовов экрана,
  `KanbanWritesRepositoryTest` — путь/глагол/тело каждого маршрута записи.

## Motion

Reveal колонок — fade + slide-up через `MotionPolicy.durationFor(
MESSAGE_REVEAL, reduced)`; при системном «уменьшить движение»
(ANIMATOR_DURATION_SCALE = 0) длительность 0 и анимация не запускается.
Кривая — decelerate (без bounce).
