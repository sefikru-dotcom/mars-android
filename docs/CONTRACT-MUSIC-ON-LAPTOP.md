# CONTRACT — волна «музыка на актуальной линии + ввод в чате во время ответа»

Репозиторий: `C:\Users\SKY\.zcode\workspace\default\mars-android-merge` (worktree, ветка `feat/music-on-laptop`,
HEAD `df8454f3` — merge музыкальной волны сервера на актуальную линию ноутбука `feat/local-chat-wave` @ `8b83f336`).
База ЗЕЛЁНАЯ: `:app-mobile:testDebugUnitTest` 3843 теста, `:core-network:test` 2696, 0 падений.

## Правила для всех воркеров (обязательны)

- **Gradle / сборку не запускать.** Сборка — только координатор. Проверяй чтением, грепом и выносом чистой логики.
  Самодельный kotlinc/classpath из кэша — запрещено (ловушка времени).
- **Коммитов не делать, `git add`/`stash`/`checkout` не делать.** Оставь изменения в рабочем дереве.
- Правишь ТОЛЬКО файлы своей зоны из таблицы. Чужие файлы — не трогать, даже если видишь в них ошибку: опиши её в отчёте.
- Новые файлы клади в свой пакет, имя — с префиксом потока (см. зоны). Новые ОБЩИЕ типы не заводить: если нужен тип из чужой зоны — опиши в отчёте, что нужно координатору.
- Строки UI — русские, в `app-mobile/src/main/res/values/strings.xml` добавляет ТОЛЬКО тот воркер, кому файл разрешён; иначе отдай строки в отчёте.
- EOL: файлы ноутбука в CRLF. Новый файл пиши с LF — не критично. Существующие файлы правь точечно (не переписывай целиком).
- `***` в выводе инструментов — маскировка секрета системой, а не содержимое файла.
- Отчёт — честный: что проверено и ЧЕМ, что НЕ проверено. Выдумывать прогон запрещено.

## Реальные ручки сервера музыки (сняты с живого `server.py` 23.11.2026, `marscore.ru/music`)

| Метод | Путь | Что |
|---|---|---|
| GET | `/api/home` | `shelf.sections[]` → полки `continue`, `new`, `for_you`, `similar` (уже в приложении: `getHome()`) |
| POST | `/api/preload/{video_id}?quality=<profile>` | предзагрузка следующего трека в серверный кэш; ответ `{"success":true,"preloading":"<id>","quality":"<profile>"}` |
| GET | `/api/stream/{video_id}?quality=original\|standard\|economy` | аудио (легаси `auto\|hq` нормализуются) |
| GET | `/api/download/{video_id}?title=&artist=&quality=` | прямой m4a, `Content-Disposition: attachment` |
| GET | `/api/favorites/export?format=m3u8\|json` | экспорт избранного |
| GET | `/api/playlist/{playlist_id}/export?format=m3u8\|json` | экспорт плейлиста; 404 `Playlist not found` |
| POST | `/api/playlist/import` body `{"url":"<YT Music URL или ID>"}` | импорт плейлиста из YouTube Music (до 100 треков) |

JSON-экспорт: `{"title": "...", "tracks":[{"id","title","artist","duration","thumbnail"}]}`.
Остальные ручки (search, charts, flow, radio, lyrics, artist, album, favorites, history, playlists CRUD,
suggestions, connect/ws) — уже реализованы в `MusicRepository`.

---

## Поток A — «ввод в чате во время ответа» (диагноз → фикс)

**Жалоба владельца:** в серверном чате (`ChatActivity`), пока модель формирует ответ, не получается написать сообщение.
Какой именно чат — не уточнён; основной подозреваемый серверный `ChatActivity`, но проверь ОБА.

**Задача ПЕРВОНАЧАЛЬНО — доказать, где ломается, а не чинить по гипотезе.** Владелец уже жаловался на голосовые/композерные вещи несколько волн подряд; третья жалоба на одно и то же = чинили не тот слой.

Ходевшие гипотезы (НЕ факты, проверь по коду):
1. На каждом SSE-кадре вызывается полный ре-рендер экрана (`scheduleStreamRender()` в `ChatActivity`), и где-то в цепочке рендера композера теряется текст/фокус/`keyListener`.
2. Кнопка отправки/steer во время потока: текст «уходит» в steer и поле очищается (`SEND_RESET`), либо отправка блокируется.
3. `ComposerInputGuardPolicy` (чистая JVM-политика) верна, но ХОСТ применяет её решения не везде: грепни всех, кто зовёт `input.setText(` и `input.text =` в `Chat*Section.kt` / `ChatActivity.kt` — мимо политики.

**Метод:** пройди путь от «пользователь нажал клавишу в EditText во время стрима» до «символ появился в поле и остался». Перечисли КАЖДОГО, кто может перезаписать `input` в этот интервал (SSE-рендер, `setStatus()`, `updateContextIndicator()`, смена модели/чипов, голосовой ввод, `restoreSteerText`, черновики, `ChatComposerSection`), и для каждого — строка, условие, что пишет. Итог — таблица виновников с номерами строк. Только после этого фикс.

**Требование продукта:** во время ответа модели поле ввода активно: можно набирать, стирать, вставлять; текст не пропадает и не подменяется; Enter/кнопка работает так же, как в покое (в этом экране во время стрима кнопка = «Стоп», отправка текста во время ответа идёт через steer — сохрани это поведение, если оно так работает; если оно сломано — почини). Клавиатура не прыгает. Для `LocalChatActivity` («Локально») — то же требование; там `LocalChatComposerBar.render()` уже заявляет «ввод не трогаем» — проверь, что это правда по коду.

**Зона (и только она):**
- `app-mobile/src/main/java/ru/mars/mobile/ChatComposerSection.kt`
- `app-mobile/src/main/java/ru/mars/mobile/ComposerInputGuardPolicy.kt`
- `app-mobile/src/main/java/ru/mars/mobile/ComposerLayoutPresenter.kt`
- `app-mobile/src/main/java/ru/mars/mobile/LocalChatComposerBar.kt`
- `app-mobile/src/main/java/ru/mars/mobile/ComposerInputTypePolicy.kt`
- НОВЫЕ файлы: `app-mobile/src/main/java/ru/mars/mobile/Composer*.kt` (префикс `Composer`), тесты `app-mobile/src/test/java/ru/mars/mobile/Composer*Test.kt`
- Существующие тесты: `ComposerInputGuardPolicyTest.kt`, `ComposerInputTypePolicyTest.kt`, `ComposerLayoutPresenterTest.kt`

**НЕ трогает:** `ChatActivity.kt`, `LocalChatActivity.kt`, все `Chat*Section.kt` кроме перечисленных, манифест, gradle-файлы, тему. Если корень зла в `ChatActivity.kt` — опиши в отчёте ТОЧНУЮ правку (файл, строка, было → стало, почему) и добавь её в раздел «Нужно координатору»; чистую логику вынеси в политику в своей зоне с тестом.

**Критерии приёмки:**
- Отчёт содержит таблицу «кто может перезаписать input во время стрима» с номерами строк и вердиктом (виновен / чист).
- Если фикс — тест, который ПАДАЕТ на старом поведении (проверяемое свойство нарушено) и проходит на новом.
- Существующие тесты композера не сломаны (не менять ассерты, чтобы позеленело).

---

## Поток B — «музыка: дотянуть Android до сервера»

Сервер 23.11 получил: предзагрузку следующего трека, импорт/экспорт плейлистов и избранного, прямое скачивание, профили качества, персональные полки. В приложении ЕСТЬ лента и качество; НЕТ `preload`, `download`, `playlist/import`, экспорта.

**Задача — три вещи, по приоритету:**

B1. **Предзагрузка следующего трека** (самое заметное: пауза между треками). В `MusicRepository` (core-network) добавить
`preload(videoId: String, quality: String)`, в плеере — вызов, когда очередь знает следующий трек (при старте текущего и при смене очереди). Политика «кого греть» — чистый JVM-класс с тестом: следующий по `MusicQueueManager` с учётом repeat/shuffle; без сети не падать; не греть тот же трек дважды подряд; не греть при пустом id.

B2. **Импорт плейлиста из YouTube Music** (URL/ID → новый плейлист на сервере). Репозиторий: `importPlaylist(url: String)`. Экран плейлистов: поле ввода URL + кнопка, состояния: ввод, «Импортирую…», готово (N треков), ошибка сервера (текст сервера показать как есть). Политика валидации URL — чистая JVM с тестом (обычная ссылка, `VL`-префикс, голый ID, мусор).

B3. **Экспорт плейлиста / избранного** в m3u8 — «поделиться»: скачивает файл через `download`/`export` и отдаёт в системный шаринг (`Intent.ACTION_SEND` + FileProvider). Проверь по манифесту, есть ли уже FileProvider (`androidx.core.content.FileProvider`) в приложении — если нет, НЕ добавляй в манифест сам (манифест — координатор): отдай в отчёт точный XML-блок для `<provider>` и пути.

**Качество = честные состояния.** Каждая сетевая операция: загрузка / успех / ошибка с текстом причины. Никаких «тихо ничего не произошло». Offline — понятная ошибка, не вечный спиннер.

**Зона (и только она):**
- `core-network/src/main/java/ru/mars/network/repository/MusicRepository.kt` — только ДОБАВЛЯТЬ методы (public API существующих методов не менять), новые приватные data class — в конец файла рядом с остальными envelope
- `core-network/src/main/java/ru/mars/network/model/MusicModels.kt` — только добавлять типы
- НОВЫЕ: `core-network/src/main/java/ru/mars/network/repository/MusicPreloadPolicy.kt`, `MusicPlaylistTransfer*.kt`
- `app-mobile/src/main/java/ru/mars/mobile/MusicPlaylistsSection.kt`
- `app-mobile/src/main/java/ru/mars/mobile/MarsMusicPlayer.kt` — точечно, только вызов preload
- НОВЫЕ: `app-mobile/src/main/java/ru/mars/mobile/MusicPreload*.kt`, `MusicPlaylistImport*.kt`, `MusicPlaylistExport*.kt`, `MusicPreloadPolicy.kt`
- Тесты: новые `core-network/src/test/java/ru/mars/network/repository/Music*Test.kt`, `app-mobile/src/test/java/ru/mars/mobile/Music*Test.kt`
- `app-mobile/src/main/res/values/strings.xml` — только добавлять строки с префиксом `music_` (файл CRLF, вставлять ПЕРЕД `</resources>`)

**НЕ трогает:** `MusicActivity.kt`, `MusicShellSection.kt`, `MarsMusicService.kt`, `MusicWidgetProvider.kt`, `MusicFullPlayer*`, `MusicNotificationPolicy.kt`, `MusicQueueManager.kt` (читать можно), манифест, gradle-файлы. Точку входа импорта/экспорта в экран (кнопку в меню плейлистов) подключит координатор — подготовь вызываемые функции с необязательными зависимостями по умолчанию и опиши в отчёте строку-вставку.

**Существующие сигнатуры (дословно из кода, компилируемые):**

```kotlin
// core-network .../repository/MusicRepository.kt (class MusicRepository)
class MusicRepository(
    private val baseUrl: String = "https://marscore.ru/music",
    private val client: OkHttpClient = HttpClients.rest,
    val deviceId: String = "android-client",
    private val restoreTimeoutMs: Long = DEFAULT_RESTORE_TIMEOUT_MS,
) {
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    suspend fun getStatus(): MusicPlayerState
    suspend fun getPlaylists(): List<Playlist>
    suspend fun createPlaylist(title: String): Playlist
    suspend fun deletePlaylist(id: String)
    suspend fun getPlaylistTracks(id: String): List<MusicTrack>
    suspend fun addTrackToPlaylist(id: String, videoId: String)
    suspend fun removeTrackFromPlaylist(id: String, videoId: String)
    // приватные хелперы, доступные внутри класса:
    private suspend fun postText(path: String, json: String): String
    private suspend fun sendJson(path: String, json: String, method: String): String
    private fun pathWithSegment(path: String, segment: String): String
    private fun requireSuccess(path: String, response: MutationResponse)
    private suspend fun getRaw(path: String, segment: String): RawResponse
    private suspend fun <T> getJson(path: String, query: List<Pair<String, String>> = emptyList(), parse: (ResponseBody) -> T): T
}
private data class MutationResponse(val success: Boolean? = null, val status: String? = null, val error: String? = null)

// core-network .../model/MusicModels.kt
package ru.mars.network.model
data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val duration: String = "--:--",
    val thumbnail: String? = null,
    val albumTitle: String? = null,
    val albumBrowseId: String? = null,
)
data class Playlist(val id: String, val title: String, val createdAt: String, val trackCount: Int)

// app-mobile .../MarsMusicPlayer.kt
object MarsMusicPlayer {
    val queueManager: MusicQueueManager
    val currentPosition: Int
    val currentTrack: MusicTrack?          // проверь тип по файлу перед использованием
    fun streamUrlFor(trackId: String): String
    fun playTrack(index: Int, queue: List<MusicTrack>)
    fun enqueue(track: MusicTrack)
}
```

**Критерии приёмки B:**
- Тесты на чистые политики (preload: repeat/shuffle/пустая очередь/тот же трек; валидация URL импорта: 4 случая).
- Каждый новый метод репозитория имеет тест на парсинг ответа через Moshi (в `core-network` `org.json` в JVM-тестах — заглушка, парсить ТОЛЬКО Moshi; числа из Moshi — `Double`, приводить через `Number.toInt()`).
- В отчёте: точный список «нужно координатору» (кнопки в экран, манифест, строки).

## Чего НЕ делать обоим
- Не менять номера версий, не трогать `build.gradle.kts`, манифест, тему, навигацию.
- Не «улучшать» чужие файлы и существующие публичные API.
- Не запускать Gradle, kotlinc, adb.
