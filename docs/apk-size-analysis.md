# Анализ размера release-APK `app-mobile` и сужение keep-правила

Волна `w1510g` · ветка `w1510g/apk-slim` · модуль `:app-mobile`.
Все цифры получены реальными сборками `assembleRelease` в этом worktree
(`flock … ./gradlew --no-daemon --max-workers=1`), разбор — скриптами по
zip/dex/mapping артефактам. Ничего не оценено «на глаз».

## 1. Замеры до/после

| метрика | до (широкий keep) | после (сужен) | Δ |
|---|---:|---:|---:|
| APK (unsigned), байт | 5 632 694 | **5 201 669** | **−431 025 (−7.65 %)** |
| `classes.dex`, байт | 4 347 264 | **3 916 464** | **−430 800 (−9.91 %)** |
| `resources.arsc`, байт | 458 800 | 458 800 | 0 |
| классы в dex | 4 778 | 4 625 | −153 |
| методы в dex | 28 266 | 25 457 | −2 809 |
| строки (strings) | 25 278 | 22 913 | −2 365 |
| поля / типы / прототипы | 16 235 / 6 024 / 7 679 | 15 806 / 5 867 / 6 901 | − |
| классы `ru.mars.*` (по коду) | 1 186 | 1 038 | −148 |
| байты кода `ru.mars.*` | 659 100 | 517 934 | −141 166 |
| классы `ru/mars` с исходным именем в dex | 529 | 286 | −243 |

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
```

### Почему это безопасно

Весь рефлексионный сериализационный путь в `ru.mars.network` — это **ровно** модели
`MusicRepository`. Он единственный строит Moshi с `KotlinJsonAdapterFactory`:

- `MusicRepository.kt:43` — `Moshi.Builder().add(KotlinJsonAdapterFactory()).build()`;
- 22 вложенных data-класса помечены `@JsonClass(generateAdapter = false)`
  (`Session`, `TrackDto`, `HomeEnvelope`, `ShelfDto`, `PlaylistDto`, …).

`KotlinJsonAdapterFactory` читает Kotlin-метаданные, primary-конструктор и имена
свойств — поэтому именно эти классы обязаны дожить до рантайма с именами и членами.

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

Сборка release прошла (`BUILD SUCCESSFUL`), затем подтверждено по dex:

- все 54 класса `MusicRepository$*` присутствуют в dex под **исходными именами**;
- структура членов всех 54 классов **байт-в-байт совпадает** с базовой сборкой
  (0 изменённых, 0 пропавших);
- имена полей сохранены точно: `TrackDto` → `id/title/artist/duration/thumbnail`,
  `Session` → `track/queue/currentIndex/currentTime/volume/isPlaying/activeDeviceId/timestamp`;
- конструкторы (включая synthetic `DefaultConstructorMarker`) и residual-сигнатуры
  сохранены; `kotlin.Metadata`, `@Json`/`@JsonClass`, `Signature`/`InnerClasses`/
  `EnclosingMethod` keep-правила оставлены без изменений.

Т.е. R8 не трогал рефлексионно-значимую часть: обфускация и сокращение применились
только к прямому Kotlin-коду, который по имени не ищется.

### Тесты

```
:app-mobile:testDebugUnitTest     — 2025 тестов, 0 failures, 0 errors
:core-network:testDebugUnitTest   —  925 тестов, 0 failures, 0 errors
```

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

- Легитимная, проверенная экономия: **−431 025 байт APK (−7,65 %)**, целиком за счёт
  dex, без потери функциональности и без изменения сериализации.
- Причина «раздувания» до ~5,6 МБ — не регресс R8 (он работал), а новые тяжёлые
  зависимости (media3, FCM) плюс постоянный `kotlin-reflect`; широкий keep-правило
  `ru.mars.network.**` добавляло к этому ≈430 КБ, которые и удалось вернуть.
