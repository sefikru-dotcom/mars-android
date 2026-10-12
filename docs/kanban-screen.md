# Read-only экран «Канбан» (app-mobile) — документ для интегратора

Мобильная витрина kanban-доски: `GET /api/kanban/board` (колонки/задачи/
статусы), `GET /api/kanban/stats` (сводка) и `GET /api/kanban/tasks/{id}`
(read-only диалог карточки). **Строго read-only**: в экране, презентере и
policy нет и не должно появиться dispatch/create/edit/move/delete —
серверный Bearer allowlist допускает только GET/HEAD `/api/kanban/`.

## Запуск из меню (для писателя ChatActivity/SidebarNavPolicy)

Экран намеренно не подключён к боковому меню: `ChatActivity` и
`SidebarNavPolicy` в этой волне заморожены. Публичная точка входа:

```kotlin
startActivity(KanbanActivity.newIntent(this))   // ru.mars.mobile.KanbanActivity
```

- Activity объявлена в `app-mobile/src/main/AndroidManifest.xml`
  (`.KanbanActivity`, не exported — запуск только изнутри приложения).
- Кнопка «Назад» и системный back — `finish()`, возврат в вызвавший экран.
- Рекомендуемое место в меню: секция с `iconKey = "ic_nav_kanban"`
  (иконка-доска `res/drawable/ic_nav_kanban.xml`, stroke 1.7 — стиль
  остальных `ic_nav_*`). Порядок в `SidebarNavPolicy.sections` контрактный,
  поэтому добавление — за волной, владеющей policy.

## Слои

- `KanbanActivity` — UI: header (назад/заголовок/прогресс/refresh),
  бейдж «доска только для чтения», notice-плашка, сводка статистики
  (summary + чипы by_status), горизонтальный свайп колонок с независимым
  вертикальным скроллом карточек, статус-блок с «Повторить». Тап по
  карточке — read-only диалог (`MarsDialog`): тело, факты, комментарии,
  связи, хвост ленты событий; единственное действие — «Закрыть».
- `KanbanPresenter` — состояния LOADING/READY/EMPTY/ERROR/NO_AUTH,
  offline-safe кэш (отказ сети при живом кэше -> ERROR со stale-колонками
  и notice, экран не пустует), best-effort статистика (отказ stats не топит
  доску). Polling-контракт: `refresh()` ходит с `since=<latest_event_id>`;
  короткий конверт `changed:false` НИКОГДА не рендерится пустой доской и не
  публикует LOADING — тихо обновляется только курсор. Дефектный
  `changed:false` без кэша деградирует в EMPTY (не crash/ERROR).
  Авто-polling каждые 30 c пока экран resumed (`POLL_INTERVAL_MS`).
- `KanbanPolicy` — чистая JVM-политика: статус→тон (ready/running/blocked/
  done/neutral), возрастные метки, подписи исполнителя/приоритета, чипы
  статистики в каноническом порядке, строки detail-диалога (только реально
  присутствующие поля). Unit-тесты: `KanbanPolicyTest`,
  `KanbanPresenterTest`.
- Сеть — существующий `KanbanRepository` (core-network, без правок;
  `dispatch` не вызывается ниоткуда в app-mobile).

## Motion

Reveal колонок — fade + slide-up через `MotionPolicy.durationFor(
MESSAGE_REVEAL, reduced)`; при системном «уменьшить движение»
(ANIMATOR_DURATION_SCALE = 0) длительность 0 и анимация не запускается.
Кривая — decelerate (без bounce).
