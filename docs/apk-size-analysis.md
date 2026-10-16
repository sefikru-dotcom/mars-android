# Анализ размера release-APK `app-mobile` и сужение keep-правила

Волна `w1510g` · ветка `w1510g/apk-slim` · модуль `:app-mobile`.
Все цифры получены реальными сборками `assembleRelease` в этом worktree
(`flock … ./gradlew --no-daemon --max-workers=1`), разбор — скриптами по
zip/dex/mapping артефактам. Ничего не оценено «на глаз».

> **Итог приёмки (актуально).** Принятая сборка на `main` — **5 216 221 Б**
> (против 5 632 694 Б до сужения, выигрыш **−416 473 Б, −7,4 %**). Колонка
> «после» в таблицах ниже (5 201 669 Б) — это промежуточная версия правила,
> в которой R8 переименовывал модели музыки; она **дефектна в рантайме** и
> служит только для сравнения состава dex. Разница 14 552 Б — цена сохранения
> рефлексионного графа `ru.mars.network.model` (см. §2 и §3).

## 1. Замеры до/после

| метрика | до (широкий keep) | промежуточно | **принято (main)** | Δ принято |
|---|---:|---:|---:|---:|
| APK (unsigned), байт | 5 632 694 | 5 201 669 | **5 216 221** | **−416 473 (−7,39 %)** |
| `classes.dex`, байт | 4 347 264 | 3 916 464 | 3 931 016 | −416 248 |
| `resources.arsc`, байт | 458 800 | 458 800 | 458 800 | 0 |
| классов в dex | 4 778 | 4 625 | 4 630 | −148 |
| методов в dex | 28 266 | 25 457 | 25 561 | −2 705 |
| строк в dex | 25 278 | 22 913 | 22 966 | −2 312 |
| полей / типов / прототипов | 16 235 / 6 024 / 7 679 | 15 806 / 5 867 / 6 901 | 15 818 / 5 874 / 6 922 | −417 / −150 / −757 |

Колонки «до» и «промежуточно» — из отчёта воркера (тот же способ подсчёта по
заголовку dex); колонка «принято» измерена заново по артефакту `main` скриптом
по `classes.dex`. Разница между «промежуточно» и «принято» — ровно те 8 моделей
`ru.mars.network.model`, что восстановлены keep-правилами.

Выигрыш целиком в dex: `resources.arsc` и `res/**` не изменились. Dex — вообще
доминанта APK (77 % до, 75 % после); native-библиотек в приложении нет.

## 2. Что именно было узко снято

Было (создано коммитом `15f5585`, файл `app-mobile/proguard-rules.pro`):

```
-keep class ru.mars.network.** { *; }
```

Стало:

```
-keep class ru.mars.network.repository.MusicRepository { *; }
-keep class ru.mars.network.repository.MusicRepository$* { *; }
-keep class ru.mars.network.model.MusicTrack { *; }
-keep class ru.mars.network.model.PlaybackStatus { *; }
-keep class ru.mars.network.model.MusicPlayerState { *; }
-keep class ru.mars.network.model.MusicSearchResult { *; }
-keep class ru.mars.network.model.LyricsResult { *; }
-keep class ru.mars.network.model.Playlist { *; }
-keep class ru.mars.network.model.MusicShelf { *; }
-keep class ru.mars.network.model.HistoryEntry { *; }
```

### Почему это безопасно

Рефлексионный сериализационный путь в `ru.mars.network` — это **не только**
`MusicRepository`, но он и музыкальные модели из `ru.mars.network.model`.
`MusicRepository` единственный строит Moshi с `KotlinJsonAdapterFactory`:

- `MusicRepository.kt:43` — `Moshi.Builder().add(KotlinJsonAdapterFactory()).build()`;
- 22 вложенных data-класса помечены `@JsonClass(generateAdapter = false)`
  (`Session`, `TrackDto`, `HomeEnvelope`, `ShelfDto`, `PlaylistDto`, …).

`KotlinJsonAdapterFactory` читает Kotlin-метаданные, primary-конструктор и имена
свойств — поэтому именно эти классы обязаны дожить до рантайма с именами и членами.

**Важная поправка.** Первоначальная версия правила сохраняла только
`MusicRepository`, и это был **runtime-дефект**: модели музыки из
`ru.mars.network.model` (`MusicTrack`, `LyricsResult`, `PlaybackStatus`,
`MusicPlayerState`, `MusicSearchResult`, `Playlist`, `MusicShelf`, `HistoryEntry`)
передаются в тот же рефлексионный `moshi.adapter(...)` как типы-аргументы, поэтому
R8 переименовывал их вместе с полями. По `mapping.txt` это выглядело так:

```
ru.mars.network.model.LyricsResult  -> m9.f1   (translated -> a)
ru.mars.network.model.MusicTrack    -> m9.n1   (id -> a)
ru.mars.network.model.PlaybackStatus -> m9.o1
```

Следствие: необязательные поля молча становились `null`, обязательные
(`MusicTrack.id`) бросали `JsonDataException` — и только в release, то есть на
устройстве. Дебажные тесты этого не видят: R8 в них выключен. Замер «до/после»
ниже (5 201 669 Б) относится именно к этой дефектной версии; рабочая сборка с
сохранением моделей даёт **5 216 221 Б** — цена корректности ≈14 КБ.

Остальной слой `ru.mars.network` рефлексией по классам не сериализуется:

- все прочие репозитории/кэши парсят JSON в `Map<String, Any?>` через
  `moshi.adapter<Map<String,Any?>>(mapType)` — Moshi над generic-типами
  `Map`/`List`/`String`, без рефлексии по классам-моделям;
- `Retrofit` объявлен зависимостью, но `retrofit.create(...)` нигде не вызывается —
  `MarsApi` используется только ради константы `BASE_URL`; в dex `retrofit2`
  даёт **0 классов** и до, и после;
- `Class.forName` / `getDeclaredField` / `getDeclaredMethod` в проде отсутствуют
  (единственные вхождения — в тестах `core-network` и `app-mobile` androidTest);
- `Gson`, `kotlinx.serialization`, `org.json` в main-коде не используются;
- `AndroidKeyStoreCredentialStore` использует JCA и вызывается напрямую из Kotlin.

## 3. Проверка, что сериализация не сломана

Сборка release прошла (`BUILD SUCCESSFUL`), затем подтверждено по dex и `mapping.txt`:

- все 54 класса `MusicRepository$*` присутствуют в dex под **исходными именами**
  (плюс 8 моделей `ru.mars.network.model` из списка выше — итого 9 сохранённых
  записей рефлексионного графа проверяет гейт `scripts/apk-contract-check.py`);
- структура членов всех 54 классов **байт-в-байт совпадает** с базовой сборкой
  (0 изменённых, 0 пропавших);
- имена полей сохранены точно: `TrackDto` → `id/title/artist/duration/thumbnail`,
  `Session` → `track/queue/currentIndex/currentTime/volume/isPlaying/activeDeviceId/timestamp`;
- конструкторы (включая synthetic `DefaultConstructorMarker`) и residual-сигнатуры
  сохранены; `kotlin.Metadata`, `@Json`/`@JsonClass`, `Signature`/`InnerClasses`/
  `EnclosingMethod` keep-правила оставлены без изменений;
- остальные ~490 классов `ru.mars.network.**` обфусцированы (в dex нет исходных
  имён .kt сверх порога) — это и дало экономию.

Т.е. R8 не трогал рефлексионно-значимую часть: обфускация и сокращение применились
только к прямому Kotlin-коду, который по имени не ищется.

### Тесты и гейт

```
:app-mobile:testDebugUnitTest     — 2025 тестов, 0 failures, 0 errors
:core-network:testDebugUnitTest   —  925 тестов, 0 failures, 0 errors
scripts/apk-contract-check.py     — PASS (рефлексионный граф: сохранено классов = 9)
tests/test_apk_contract_check.py  —  22 теста, OK
```

Дебажные тесты **не** являются проверкой R8 — они идут на дебаг-варианте, где
минификация выключена. Поэтому контракт вынесен в `scripts/apk-contract-check.py`
и подключён в CI: он падает `rc=1` с готовым списком `-keep`, если R8 переименовал
класс рефлексионного графа.

## 4. Почему APK вырос с ~3,3 до ~5,6 МБ (реальная причина)

Раньше «базовые» 3,3 МБ не включали два тяжёлых функциональных блока. По артефактам
CI (сборка `app-mobile-release-unsigned.apk` на версиях 26/27/31; разбор dex по mapping):

| версия | APK, байт | dex, байт | `androidx.media3` | `kotlin.reflect` | `ru.mars.*` | firebase/gms |
|---|---:|---:|---:|---:|---:|---:|
| 1.5.0/1.5.1 (26/27) | 3 292 602 | 2 776 576 | 0 | ~545 700 | ~464 700 | 0 |
| 1.5.4 (30) | 4 999 910 | — | — | — | — | — |
| 1.5.5 (31) | 5 569 578 | 4 295 356 | ~526 000 | ~530 600 | ~659 100 | ~144 500 |

Рост dex v27 → v31 (+1,5 МБ) даёт в основном **легитимная новая функциональность**:

- `androidx.media3` (ExoPlayer-движок аудио, коммит `9ca8aa1`) — **+~526 КБ** кода;
- Firebase/GMS (FCM push, коммит `3ff65ea`) — **+~144 КБ**;
- новые фичи (kanban/analytics/logs/profiles/music-home/локальные сохранения) —
  `ru.mars.*` **+~195 КБ** и сопутствующий рост `androidx.*`.

`kotlin.reflect` (~530 КБ, ~21 % кода) присутствует **в обеих** сборках и к росту не
относится — это постоянная плата за рефлексионный Moshi (см. ниже).

## 5. Оставшиеся левереджи (не реализованы — осознанно)

1. **`kotlin-reflect` ≈ 530 КБ кода (~21 % dex), 1 079 классов.** Тянется
   транзитивно как `moshi-kotlin` ← `org.jetbrains.kotlin:kotlin-reflect`. Нужен
   только `KotlinJsonAdapterFactory` в `MusicRepository`. Убрать = перевести
   `MusicRepository` на Moshi-codegen (`@JsonClass(generateAdapter = true)` + KSP)
   либо на ручной парсинг (`Map<String,Any?>`, как остальной модуль). **Не сделано:**
   это функциональная правка контракта парсинга музыки, требует codegen-плагина
   (KSP/moshi-kotlin-codegen), которого нет в локальном кэше Gradle (офлайн-сборка
   невозможна) — выходит за рамки «сужения keep» и рискует сломать музыку.
2. **`androidx.media3` ≈ 526 КБ.** Используется реально (`MarsMusicPlayer`,
   `AndroidServerTtsPlayback`, `PlaybackRetryPolicy`), отдельные артефакты
   (`media3-exoplayer`, `media3-datasource`) уже раздельны; `media3-datasource`
   дублирует транзитивную зависимость, но её удаление размер не меняет.
   Сокращать нечем без потери функциональности.
3. `resources.arsc` (459 КБ) и иконки — вне темы dex; `shrinkResources` уже включён.

## 6. Итог

- Легитимная, проверенная экономия: **−416 473 байт APK (−7,39 %)**, целиком за счёт
  dex, без потери функциональности и без изменения сериализации.
- Причина «раздувания» до ~5,6 МБ — не регресс R8 (он работал), а новые тяжёлые
  зависимости (media3, FCM) плюс постоянный `kotlin-reflect`; широкий keep-правило
  `ru.mars.network.**` добавляло к этому ≈430 КБ, которые и удалось вернуть.
