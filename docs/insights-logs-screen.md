# Экран мониторинга Insights + Logs (app-mobile) — документ для интегратора

Read-only экран «Мониторинг» с двумя вкладками: **Инсайты** (`GET /api/insights`,
фильтр периода) и **Логи** (`GET /api/logs`, whitelisted файл + tail). Никаких
мутаций: экран только читает снимки/хвосты; файл лога и tail — серверные enum'ы
`{agent, errors, gateway}` × `{100, 200, 500, 1000}`, произвольные пути логов
непредставимы типом.

## Запуск из меню (для писателя ChatActivity/SidebarNavPolicy)

Экран намеренно не подключён к боковому меню: `ChatActivity` и
`SidebarNavPolicy` в этой волне заморожены. Публичная точка входа:

```kotlin
startActivity(InsightsActivity.newIntent(this))                     // вкладка Инсайты
startActivity(InsightsActivity.newIntent(this, MonitorTab.LOGS))    // сразу Логи
```

- Activity объявлена в `app-mobile/src/main/AndroidManifest.xml`
  (`.InsightsActivity`, не exported — запуск только изнутри приложения).
- Кнопка «Назад» и системный back — `finish()`, возврат в вызвавший экран.
- Рекомендуемое место в меню: `SidebarSection(id = "insights",
  label = "Мониторинг", iconKey = "ic_insights", target = NavTarget.INSIGHTS)`
  (иконки `res/drawable/ic_insights.xml` / `ic_logs.xml`, stroke 1.7 — стиль
  остальных `ic_nav_*`). Порядок в `SidebarNavPolicy.sections` контрактный,
  поэтому добавление — за волной, владеющей policy.

## Слои

- `InsightsActivity` — UI: header (назад/заголовок/прогресс/refresh), общий
  notice, строка вкладок (pill-табы с иконками), панель Инсайтов (чипы
  периода 1/7/30/90 дней + карточки секций) и панель Логов (чипы файла и
  tail, monospace-строки окна, подпись «строк: N · размер»). Safe-area через
  WindowInsets; анимации — `MotionPolicy` с уважением reduce-motion
  (`ANIMATOR_DURATION_SCALE`), все цвета/фоны из `MarsTheme`.
- `InsightsPresenter` / `LogsPresenter` — чистые JVM-презентеры: состояния
  LOADING/READY/EMPTY/ERROR/NO_AUTH, offline-safe (прошлые строки/ряды
  переживают сбой загрузки и показываются с notice «Нет сети — показаны
  последние …»), порт fetch инжектится. Конкурентные загрузки защищены
  счётчиком поколения: устаревший ответ более раннего запроса отбрасывается и
  не затирает состояние более нового; `CancellationException` пробрасывается
  дальше, а не превращается в ERROR (раньше его глотал `runCatching`).
- `InsightsPolicy` / `LogsPolicy` — чистые тексты и маппинг доменных моделей
  (`InsightsSnapshot`, `ServerLogWindow`) в UI-ряды. Инсайты: тонированные
  итоги периода (сессии/сообщения/токены вход-выход/кеш-прочтения и
  попадание в кеш/стоимость), модели (рендер-лимит 12 + note «Показаны
  первые N из M»), токены по дням (бар-ряды с весом от максимума, рендер-лимит
  последних 31 дня), активность по дням недели (Пн..Вс) и по часам (00..23);
  all-zero секции серверного zero-fill пропускаются. Логи: подписи
  файла/tail/байт, серверный `hint` для пустого файла.

## Данные и auth

- Репозитории: `InsightsRepository().getInsights(MarsApi.BASE_URL, credential,
  days = …)` и `LogsRepository().getLogs(MarsApi.BASE_URL, credential, file,
  tail)` — device Bearer из `AndroidKeyStoreCredentialStore` (тот же
  credential-путь, что ChatActivity/SpacesActivity).
- Контракт `GET /api/insights` проверен read-only по деплойд-обработчику
  `/opt/hermes-webui/api/route_insights.py` (`build_insights_response`):
  единственный query-параметр `days` — сервер clamp'ит его в 1..365, при
  отсутствии/мусоре берёт 30; валидационных веток **нет** — 400/503 этот
  маршрут не возвращает никогда. 200 несёт usage-payload:
  `{period_days, total_sessions, total_messages, total_input_tokens,
  total_output_tokens, total_cache_read_tokens, total_cache_hit_percent,
  total_tokens, total_cost, models[], daily_tokens[], activity_by_day[]
  (7 weekday-бакетов Mon..Sun), activity_by_hour[] (24 бакета)}`;
  `*_cache_hit_percent` — int 0..100 или `null`, стоимости — float,
  `daily_tokens` сервер zero-fill'ит до ровно `period_days` строк. Парсинг
  толерантен к отсутствующим/мусорным полям (дефолты, без падений).
  Не-2xx возможен только от auth-guard/диспетчера (401/403/404/500 c
  `{"error": …}`) — клиент мапит всё в `InsightsResult.Failed`.
- Ошибки: у инсайтов любой сбой — общий текст (`MSG_ERROR`) + offline-вариант;
  у логов серверный 400 «Unknown log file» — своим текстом
  (`LogsPolicy.msgInvalidRequest`), транспорт — общим + offline-вариант.
- Период — whitelist `[1, 7, 30, 90]` дней (`InsightsPolicy.sanitizePeriod`),
  дефолт 30 — серверный default `days`. Вкладка Logs грузится лениво (при
  первом открытии вкладки).

## Тесты (чистые JVM, без Robolectric)

- `InsightsRepositoryTest` — контракт на MockWebServer с fixture реального
  payload, снятого с деплойд-обработчика (days=7, изолированный синтетический
  индекс сессий): маппинг `days`, полный разбор totals/models/daily_tokens/
  activity_by_day/activity_by_hour, толерантность к мусору/строковым числам,
  честная лестница ошибок (без выдуманных 400/503).
- `InsightsPolicyTest` — whitelist периода и серверный default 30,
  плюрализация, форматтеры (группировка тысяч, `$`-стоимость, даты/часы),
  isEmpty по серверным totals (zero-filled ряды пустоту не отменяют), секции,
  доли моделей, бар-веса, рендер-лимиты с note.
- `LogsPolicyTest` — точный паритет whitelist'ов с сервером, подписи
  файла/tail, формат байт, meta окна, приоритет серверного `hint`.
- `InsightsPresenterTest` / `LogsPresenterTest` — переходы состояний:
  NO_AUTH без fetch, READY/EMPTY/ERROR, offline-тексты, offline-safe
  сохранение прошлых данных, смена периода/файла/tail, гонки конкурентных
  загрузок (stale-ответ не затирает свежий в обе стороны) и проброс
  `CancellationException`.

Прогон: `./gradlew :core-network:testDebugUnitTest :app-mobile:testDebugUnitTest --no-daemon --max-workers=1`.
