package ru.mars.mobile

import ru.mars.network.repository.MusicAlbumSummary

/**
 * Чистая политика навигации по альбомам (JVM, без Android-импортов).
 *
 * Контракт сервиса, из которого выросли правила (проверено на живом API):
 * - список альбомов берётся из `GET /api/search?q=…&type=albums` — страница
 *   исполнителя (`/api/artist/…`) альбомов не отдаёт вовсе;
 * - открыть альбом можно только по browseId с префиксом `MPRE`: сервер отвечает
 *   `must start with MPRE` и кодом 500 на всё остальное, поэтому такой запрос
 *   отсекается ДО сети — иначе пользователь увидит «проверьте сеть» там, где сеть
 *   в порядке, а виноват id;
 * - карточки одного альбома могут прийти дважды (поиск по имени исполнителя
 *   склеивает выдачу из нескольких источников), поэтому список дедуплицируется по
 *   `browseId` с сохранением серверного порядка: перестановка карточек при
 *   повторной загрузке выглядит как «список прыгает».
 *
 * Тексты состояний живут здесь, а не в слое вью: диалог альбома и секция
 * «Альбомы» в диалоге исполнителя должны говорить одно и то же.
 */
object MusicAlbumPolicy {

    /** Префикс browseId альбома; всё остальное сервер отвергает. */
    const val BROWSE_ID_PREFIX = "MPRE"

    /** Заголовок диалога альбома. */
    const val TITLE = "Альбом"

    /** Заголовок секции альбомов в диалоге исполнителя. */
    const val SECTION_TITLE = "Альбомы"

    /** Первое состояние секции, пока ответа нет. */
    const val LOADING = "Загружаем альбомы…"

    /** Первое состояние диалога альбома, пока ответа нет. */
    const val LOADING_ALBUM = "Загружаем альбом…"

    /** Поиск ответил, но альбомов у исполнителя нет. */
    const val EMPTY = "Альбомов нет"

    /** Альбом открылся, но треков в нём нет. */
    const val EMPTY_TRACKS = "Треки не найдены"

    /** Сервер отказал по browseId — открывать нечего, повтор не поможет. */
    const val NOT_FOUND = "Альбом не найден"

    /** Сеть/сервер: повтор осмыслен. */
    const val NETWORK_FAILURE = "Не удалось загрузить альбомы — проверьте сеть"

    /** Сеть/сервер в диалоге альбома. */
    const val NETWORK_FAILURE_ALBUM = "Не удалось загрузить альбом — проверьте сеть"

    /** Кнопка повтора после сбоя. */
    const val RETRY = "Повторить"

    /** Кнопка закрытия диалога. */
    const val CLOSE = "Закрыть"

    /**
     * browseId для `GET /api/album/{browseId}`: обрезанный id с префиксом `MPRE`
     * либо null — открывать нечего.
     *
     * Регистр важен: сервер сравнивает префикс как есть, `mpreb_x` он не примет
     * (проверено — отказ по префиксу), поэтому «починить» регистр клиент не может.
     */
    fun requestTarget(rawBrowseId: String?): String? {
        val value = rawBrowseId?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (!value.startsWith(BROWSE_ID_PREFIX)) return null
        return value
    }

    /**
     * Слияние свежей выдачи с уже показанной: дедуп по `browseId`, порядок
     * сохраняется — сначала серверный порядок [remote], затем карточки из
     * [existing], которых в свежей выдаче не оказалось.
     *
     * Зачем [existing] вообще нужен: повторный запрос после сбоя может вернуть
     * неполную выдачу, и уже показанные карточки не должны из-за этого исчезнуть.
     * Строки без `browseId` отбрасываются: такую карточку нельзя открыть.
     */
    fun mergeSummaries(
        remote: List<MusicAlbumSummary>,
        existing: List<MusicAlbumSummary> = emptyList(),
    ): List<MusicAlbumSummary> {
        val seen = LinkedHashSet<String>()
        val merged = ArrayList<MusicAlbumSummary>(remote.size + existing.size)
        (remote + existing).forEach { album ->
            val id = album.browseId.trim()
            if (id.isEmpty()) return@forEach
            if (!seen.add(id)) return@forEach
            merged += if (id == album.browseId) album else album.copy(browseId = id)
        }
        return merged
    }

    /**
     * Порядок карточек в секции «Альбомы»: сначала альбомы самого исполнителя,
     * затем остальная выдача — внутри каждой группы сохраняется серверный порядок.
     *
     * Зачем перестановка: `GET /api/search?q=…&type=albums` — поиск по релевантности,
     * а не «альбомы исполнителя». По «Linkin Park» из 40 карточек своя одна
     * (`Hybrid Theory`), остальные — трибьюты и каверы вида «Mano DJ — Mtg Linkin
     * Park»; без перестановки чужие альбомы стоят первыми.
     *
     * Почему это НЕ фильтр: выдача бывает полностью корректной, не имея ни одного
     * совпадения строки. Запрос `Zemfira` возвращает альбомы «Земфира» — латиница
     * против кириллицы; транслитерацию здесь делать нельзя (на коротких именах она
     * даёт ложные срабатывания, это отдельная задача). Поэтому чужие карточки не
     * выбрасываются, а уезжают вниз, и если своих не нашлось ни одной — список
     * возвращается как есть: «Альбомов нет» на корректной выдаче был бы регрессом.
     *
     * Пустое имя исполнителя — фильтровать не по чему, список не трогаем.
     */
    fun rankForArtist(albums: List<MusicAlbumSummary>, artistName: String): List<MusicAlbumSummary> {
        val needle = normalizeArtist(artistName)
        if (needle.isEmpty() || albums.isEmpty()) return albums
        val own = ArrayList<MusicAlbumSummary>(albums.size)
        val foreign = ArrayList<MusicAlbumSummary>(albums.size)
        albums.forEach { album ->
            if (sameArtist(normalizeArtist(album.artist), needle)) own += album else foreign += album
        }
        // Своих нет — значит, совпадать нечему (другой алфавит/написание): порядок
        // сервера остаётся единственным осмысленным. Свои все — переставлять нечего.
        if (own.isEmpty() || foreign.isEmpty()) return albums
        return own + foreign
    }

    /** Есть ли что показывать: пустой список и список из пустых id — одно и то же. */
    fun hasAlbums(albums: List<MusicAlbumSummary>): Boolean =
        albums.any { it.browseId.isNotBlank() }

    /** Счётчик треков по-русски: «1 трек», «3 трека», «5 треков». */
    fun tracksLabel(count: Int): String = "$count ${plural(count.toLong(), "трек", "трека", "треков")}"

    /**
     * Подпись под названием альбома в диалоге: год и число треков
     * («2024 · 3 трека»). Отсутствующие части не печатаются — пустых « · »
     * и нулевого счётчика в шапке быть не должно.
     */
    fun meta(year: String?, trackCount: Int): String {
        val parts = ArrayList<String>(2)
        year?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
        if (trackCount > 0) parts += tracksLabel(trackCount)
        return parts.joinToString(" · ")
    }

    /**
     * Вторая строка карточки альбома в списке: исполнитель и год
     * («не меняет · 2024»). Отсутствующие части не печатаются, пустая строка
     * означает «второй строки нет» — карточка остаётся с одним названием.
     */
    fun subtitle(album: MusicAlbumSummary): String {
        val artist = album.artist.trim()
        val year = album.year?.trim().orEmpty()
        return when {
            artist.isEmpty() && year.isEmpty() -> ""
            artist.isEmpty() -> year
            year.isEmpty() -> artist
            else -> "$artist · $year"
        }
    }

    /**
     * TalkBack-подпись карточки: читает ДЕЙСТВИЕ тапа, как строки треков
     * («Играть: …»), — «Открыть альбом: двадцать два — не меняет».
     */
    fun cardDescription(album: MusicAlbumSummary): String {
        val artist = album.artist.trim()
        val title = album.title.trim().ifEmpty { "Без названия" }
        return if (artist.isEmpty()) "Открыть альбом: $title" else "Открыть альбом: $title — $artist"
    }

    /** Текст сбоя диалога альбома: отказ по browseId — не сеть, повтор не поможет. */
    fun failureText(notFound: Boolean): String =
        if (notFound) NOT_FOUND else NETWORK_FAILURE_ALBUM

    /** Повтор осмыслен только для сетевого сбоя. */
    fun retryAvailable(notFound: Boolean): Boolean = !notFound

    // ------------------------------------------------------------ внутреннее

    /**
     * Приведение имени исполнителя к сравнимому виду: регистр, пунктуация,
     * кавычки и лишние пробелы не должны решать, «свой» это альбом или нет.
     * Всё, что не буква и не цифра, считается разделителем слов — так
     * «Linkin Park», «LINKIN PARK» и «Linkin-Park» дают одну строку, а
     * «Mano DJ — Mtg Linkin Park» остаётся четырьмя словами.
     *
     * `lowercase()` без локали — намеренно: турецкая локаль превратила бы
     * «I» в «ı» и развалила бы сравнение латиницы.
     */
    private fun normalizeArtist(raw: String): String {
        val builder = StringBuilder(raw.length)
        var pendingSpace = false
        raw.lowercase().forEach { ch ->
            if (ch.isLetterOrDigit()) {
                if (pendingSpace && builder.isNotEmpty()) builder.append(' ')
                pendingSpace = false
                builder.append(ch)
            } else {
                pendingSpace = true
            }
        }
        return builder.toString()
    }

    /**
     * Совпал ли исполнитель альбома с искомым.
     *
     * Сравнение — равенство или вхождение ЦЕЛЫМ словом с начала строки:
     * «linkin park» и «linkin park feat x» — свои, «mano dj mtg linkin park» —
     * чужой (имя лишь встречается внутри, но альбом не его). Наивный поиск
     * подстроки здесь дал бы «своим» любой кавер, у которого имя исполнителя
     * приписано в хвосте, — ровно тот дефект, который чиним.
     */
    private fun sameArtist(candidate: String, needle: String): Boolean {
        if (candidate.isEmpty()) return false
        if (candidate == needle) return true
        return candidate.startsWithWord(needle) || needle.startsWithWord(candidate)
    }

    /** [this] начинается с [prefix] и граница проходит по слову, а не по букве. */
    private fun String.startsWithWord(prefix: String): Boolean =
        length > prefix.length && startsWith(prefix) && this[prefix.length] == ' '

    /** Русская форма числительного: 1 → one, 2..4 → few, остальное → many. */
    private fun plural(count: Long, one: String, few: String, many: String): String {
        val mod100 = count % 100
        if (mod100 in 11..14) return many
        return when (count % 10) {
            1L -> one
            2L, 3L, 4L -> few
            else -> many
        }
    }
}
