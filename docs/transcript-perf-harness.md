# Transcript perf harness (JVM, wire 100/500/1000)

Назначение: воспроизводимый бенчмарк/контракт-харнесс политик рендера транскрипта
на историях «с провода» размером **100 / 500 / 1000 сообщений**. Чистая JVM
(`src/test`, без Robolectric и без Android-view). Миграции на RecyclerView здесь
НЕТ и харнесс её не предрешает — он фиксирует верхнюю границу работы политик и
задаёт контракт данных, которые нужно собрать на устройстве перед таким решением.

- Код: `app-mobile/src/test/java/ru/mars/mobile/perf/`
  - `TranscriptPerfFixtures.kt` — детерминированный генератор историй;
  - `TranscriptPerfHarness.kt` — прогон стадий + контракт метрик (`TranscriptPerfReport`);
  - `TranscriptPerfHarnessTest.kt` — утверждения (8 тестов).
- Запуск: `./gradlew --no-daemon :app-mobile:testDebugUnitTest --tests "ru.mars.mobile.perf.*"`
- Артефакт прогона: `app-mobile/build/reports/transcript-perf/transcript-perf-report.md`
  (stdout теста + файл; в git не коммитится — evidence-снимок приведён ниже).

## Продуктовый cap: почему «1000» ≠ «1000 карточек»

`TranscriptCapPolicy.MAX_MESSAGES = 300`: любая история с сервера режется до
**300 свежих сообщений + 1 якорь** «старые сообщения подрезаны» (итог ≤ 301),
пузырь длиннее `MAX_BUBBLE_CHARS` (96K) усекается до хвоста с якорным префиксом.
Поэтому wire=500 и wire=1000 измеряют **поглощение избытка cap'ом**, а не рендер
500/1000 карточек: UI-размер после cap одинаков (301) и не зависит от wire.
Харнесс никогда не заявляет «1000 инфлейченных карточек» — число карточек в плане
рендера ограничено cap'ом, и тест `initialRenderPlanRebuildsExactlyCappedCardsNeverWireSize`
защищает это явно (включая anti-регресс `1000 != initialFullRebuildCards`).

## Контракт фикстуры (`TranscriptPerfFixtures`)

- `(wireSize, seed)` → побайтово идентичный `WireTranscript` (seeded `java.util.Random`,
  фиксированная база и шаг меток времени; `DEFAULT_SEED = 20251001`).
- Состав: чередование user/assistant; markdown (заголовки, абзацы, буллиты,
  нумерация, code fences, цитаты, таблицы, ссылки); у каждого 5-го assistant —
  мысли, у каждого 7-го — инструменты, у каждого 23-го user — вложение.
- Метки: `BASE_TIMESTAMP_MS + i·90s` — 1000 сообщений ≈ 25 часов, гарантированное
  пересечение локальных суток (день-разделители участвуют в снимках карточек).
- Ровно один заведомо oversized-пузырь (> 96K символов) на позиции `wireSize-2` —
  всегда попадает в сохраняемый хвост и упражняет пузырьковый cap на всех размерах.
- Хвостовое сообщение — непустой assistant в статусе `STREAMING` (передний фронт
  стрима: на нём проверяется TEXT_ONLY-заплатка).
- `streamToken(seed, frame)` — детерминированный «токен» для кадров стрима.

## Контракт метрик (`TranscriptPerfHarness.run`)

Четыре стадии, каждая вызывает исключительно прод-код:

| Стадия | Прод-код | Измеряемые величины |
| --- | --- | --- |
| cap | `TranscriptCapPolicy.capMessages` | wire→ui размер, якорь/trimmed, символы wire→ui, крупнейший UI-пузырь |
| snapshots | `CardSnapshotPolicy.of` (зеркало `ChatActivity.currentSnapshots`) | число карточек, день-разделители |
| render plan | `IncrementalTranscriptPolicy.plan` | форма плана на переходах: первый рендер / кадр стрима / noop / append / ребайнд сессии; число TEXT_ONLY и FULL_CARD заплаток |
| markdown | `MarkdownFormatter.parseBlocks` (+ LRU) | тексты, кэшируемые, блоки, warm-кэш, cache-hit (same instance), oversized-не-кэшируется |

Ключевые проверяемые переходы планов (утверждаются тестами на всех wire-размерах):

- первый рендер → `FullRebuild(≤301 карточка)`;
- кадр стрима (токен в хвостовой пузырь) → `Patch`: ровно **1×TEXT_ONLY**, 0 FULL_CARD,
  0 достроек — стоимость кадра не зависит от wire-размера;
- без изменений → `NoChange` (дерево не трогается);
- новое сообщение → `Patch(+1 карточка)`;
- ребайнд другой сессии (другие тексты И метки) → `FullRebuild`. Нюанс политики:
  если у двух транскриптов совпадают даже метки/счётчики якоря, снимки якорей равны
  и план вырождается в `Patch(FULL_CARD…)` — харнесс моделирует честный ребайнд
  со сдвигом меток (см. комментарий в `run`).

**Политика таймингов.** nanoTime-медианы попадают ТОЛЬКО в отчёт (справочно).
Тесты намеренно не утверждают wall-clock пороги: общий CI-раннер даёт разброс,
строговые пороги flaky. Защищаются детерминированные счётчики работы (размеры,
якоря, форма плана, блоки, детерминизм фикстур/разбора) — они и есть «timing
proxies»: стоимость политик линейна по этим счётчикам.

## Что харнесс НЕ измеряет — нужна приборная съёмка на устройстве

JVM-харнесс доказывает, что слой политик ограничен cap'ом (O(301), кадр стрима —
O(1) карточка). Реальная стоимость UI от него не видна; для решения о миграции
контейнера сообщений нужны данные с устройства/эмулятора:

1. **Инфлейт/лэйаут карточек** — `buildCardEntry` создаёт реальные View: ms на
   карточку, суммарная стоимость `FullRebuild(301)` на главном потоке. Инструмент:
   Perfetto/systrace или `Trace.beginSection` вокруг `rebuildTranscript` на эмуляторе
   (рецепт: `references/emulator-testing.md` скилла `mars-native-android-client`).
2. **Скролл-плавность длинного транскрипта** — FPS/jank при флинге контейнера
   `LinearLayout` с 301 карточкой (сейчас все карты живут в дереве одновременно;
   у RecyclerView инфлейтились бы только видимые ~5–10). Инструмент:
   `adb shell dumpsys gfxinfo <pkg> framestats` + скрипт-свиток UiAutomator.
3. **Память/GC** — удержание view-дерева из 301 карточки (View/Drawables/спан-кеши)
   против модели «видны только вьюпорту». Инструмент: `dumpsys meminfo`, Studio
   Memory Profiler, аллокации в `onTrimMemory`-сценарии (`MemoryTrimPolicy`).
4. **Фоновый markdown-парсинг в реальном потоке** — харнесс меряет `parseBlocks`
   синхронно; на устройстве нужен профиль `Dispatchers.Default`-офлоада
   (`tryAsyncTextOnlyUpdate`, `FrameVersionGate`) под живой SSE-нагрузкой.
5. **ANR-пороги на слабых устройствах** — поведение 301-карточного дерева на
   low-end железе (minSdk 28) JVM воспроизвести не может.

Порог принятия решения по RecyclerView: пункты 1–3 на эмуляторе/устройстве при
capped-транскрипте (301 карточка). Если `FullRebuild` и флинг остаются в кадровом
бюджете — миграция не требуется; JVM-харнесс уже гарантирует, что рост wire-истории
(500/1000/…) UI-стоимость не увеличивает.

## Evidence-снимок прогона

Хост: Linux CI-раннер Марса (6 vCPU), JDK unit-test, ветка `wave3/transcript-harness`.
Прогон: `:app-mobile:testDebugUnitTest --tests "ru.mars.mobile.perf.*"` — 8 tests, 0 failures.
Тайминги справочные (медианы), абсолютные значения на другом железе не значимы;
значимы счётчики (ui-размеры, форма планов, блоки).

```text
# Transcript perf harness — JVM report

Прогон: wire 100/500/1000; cap=300; тайминги справочные (медианы nanoTime), утверждаются счётчики работы.

## wire=100 seed=20251001
fixture : bubbles=100, chars=316749, largestBubble=142296, oversizedBubbles=2, thoughts=10, tools=7, attachments=2
cap     : wire 100 -> ui 100 (anchor=false, trimmed=0), chars 316749 -> 230147, largestUiBubble=98335, capMessages median 0.179 ms (5 runs)
snapshot: cards=100, daySeparators=1, rescan median 0.709 ms
plan    : initial=FullRebuild(100 cards); streamFrame=Patch(textOnly=1, fullCard=0, appended=0); noop=NoChange; append=Patch(+1); rebind=FullRebuild
plan    : snapshotRescan median 0.182 ms, plan median 0.039 ms
markdown: texts=100, cacheable=98, blocks=140, warmCache=64, warmHitSameInstance=true, oversizedUncached=true, cold median 16.087 ms, warm median 0.010 ms

## wire=500 seed=20251001
fixture : bubbles=500, chars=597189, largestBubble=142296, oversizedBubbles=3, thoughts=50, tools=35, attachments=11
cap     : wire 500 -> ui 301 (anchor=true, trimmed=200), chars 597189 -> 204497, largestUiBubble=98335, capMessages median 0.834 ms (5 runs)
snapshot: cards=301, daySeparators=1, rescan median 0.259 ms
plan    : initial=FullRebuild(301 cards); streamFrame=Patch(textOnly=1, fullCard=0, appended=0); noop=NoChange; append=Patch(+1); rebind=FullRebuild
plan    : snapshotRescan median 0.164 ms, plan median 0.042 ms
markdown: texts=300, cacheable=299, blocks=450, warmCache=64, warmHitSameInstance=true, oversizedUncached=true, cold median 4.189 ms, warm median 0.007 ms

## wire=1000 seed=20251001
fixture : bubbles=1000, chars=924074, largestBubble=143820, oversizedBubbles=4, thoughts=100, tools=71, attachments=22
cap     : wire 1000 -> ui 301 (anchor=true, trimmed=700), chars 924074 -> 306638, largestUiBubble=98335, capMessages median 0.678 ms (5 runs)
snapshot: cards=301, daySeparators=2, rescan median 0.296 ms
plan    : initial=FullRebuild(301 cards); streamFrame=Patch(textOnly=1, fullCard=0, appended=0); noop=NoChange; append=Patch(+1); rebind=FullRebuild
plan    : snapshotRescan median 0.288 ms, plan median 0.034 ms
markdown: texts=300, cacheable=298, blocks=448, warmCache=64, warmHitSameInstance=true, oversizedUncached=true, cold median 6.460 ms, warm median 0.006 ms

JVM harness меряет только политики; инфлейт/лэйаут/скролл/FPS/память — приборная съёмка на устройстве (docs/transcript-perf-harness.md).
```

Выводы снимка: UI-размер отвязан от wire (500 и 1000 → одинаковые 301); кадр
стрима — одна TEXT_ONLY-заплатка на любом размере; cap-подрезка и скан снимков —
субмиллисекунды даже на wire=1000; холодный markdown-разбор всего capped-транскрипта
единицы-десятки ms, warm-проход (LRU-кэш) — микросекунды. Узкое место, которое
JVM показать не может, — стоимость 301 реальной View в дереве: пункты 1–3 выше.
