package ru.mars.mobile

import ru.mars.network.model.MusicTrack

/**
 * Чистая политика перехода к исполнителю (`GET /api/artist/{id_or_name}`).
 *
 * Что решает политика, а не слой вью:
 * - **какой id слать**: сервер различает имя и channelId по префиксу `UC`, поэтому
 *   строка уходит как есть, но пустое значение и browseId альбома (`MPRE…`)
 *   отсекаются — такой запрос гарантированно вернёт отказ, и открывать нечего;
 * - **дедупликация и размер**: страница исполнителя приходит с повторами (одна
 *   песня может стоять и в топе, и в полке), поэтому список чистится по `id` и
 *   ограничивается [MAX_SONGS] — очередь не раздувается до сотен строк;
 * - **подписи**: подписчики сервера («19.1K») и счётчик песен по-русски;
 * - **тексты состояний**: загрузка, «не найден», сбой сети — экран не придумывает
 *   формулировки сам.
 *
 * Без Android-импортов — покрывается JVM unit-тестами.
 */
object MusicArtistPolicy {

    /** Префикс channelId: с него сервер идёт прямо в канал, иначе ищет по имени. */
    const val CHANNEL_PREFIX = "UC"

    /** Префикс browseId альбома; `/api/artist` его не принимает (это не исполнитель). */
    const val ALBUM_PREFIX = "MPRE"

    /** Предел песен в диалоге исполнителя: больше — уже не «топ», а свалка. */
    const val MAX_SONGS = 50

    /** Заголовок диалога. */
    const val TITLE = "Исполнитель"

    /** Первое состояние тела диалога, пока ответа нет. */
    const val LOADING = "Загружаем исполнителя…"

    /** Сервер ответил 404: такого исполнителя у него нет. */
    const val NOT_FOUND = "Исполнитель не найден"

    /** Сеть/сервер: повтор осмыслен. */
    const val NETWORK_FAILURE = "Не удалось загрузить исполнителя — проверьте сеть"

    /** Страница нашлась, но песен в ней нет. */
    const val EMPTY_SONGS = "Песни не найдены"

    /** Имя в строке трека пустое — переходить некуда. */
    const val UNKNOWN_ARTIST = "Исполнитель неизвестен"

    /** Кнопка повтора после сбоя. */
    const val RETRY = "Повторить"

    /** Кнопка закрытия диалога. */
    const val CLOSE = "Закрыть"

    /**
     * Значение для пути `/api/artist/{…}`: имя или channelId как есть (обрезанное),
     * либо null — открывать нечего (пусто или browseId альбома).
     */
    fun requestTarget(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.startsWith(ALBUM_PREFIX)) return null
        return value
    }

    /** Имя пригодно для перехода: тап по пустой подписи ничего не запрашивает. */
    fun canOpen(raw: String?): Boolean = requestTarget(raw) != null

    /** Строка похожа на channelId (`UC…`), а не на имя исполнителя. */
    fun isChannelId(raw: String?): Boolean {
        val value = raw?.trim().orEmpty()
        return value.length > CHANNEL_PREFIX.length && value.startsWith(CHANNEL_PREFIX)
    }

    /**
     * Песни для диалога: без пустых `id` (такую строку нечем играть), без дублей по
     * `id` (порядок первого вхождения сохраняется) и не длиннее [limit].
     */
    fun songs(raw: List<MusicTrack>, limit: Int = MAX_SONGS): List<MusicTrack> =
        raw.asSequence()
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .take(limit.coerceAtLeast(0))
            .toList()

    /**
     * Имя для шапки диалога ДО ответа сервера: имя печатаем сразу (пользователь видит,
     * куда попал), а channelId (`UC…`) — нет, это техническая строка.
     */
    fun initialName(raw: String?): String {
        val value = raw?.trim().orEmpty()
        return if (isChannelId(value)) "" else value
    }

    /**
     * Подпись подписчиков: «19.1K» → «19,1 тыс. подписчиков», «1.2M» → «1,2 млн
     * подписчиков», «1234» → «1 234 подписчика». Пустое или нечисловое значение
     * даёт null — строку в UI просто не показываем, а не печатаем мусор сервера.
     */
    fun subscribersLabel(raw: String?): String? {
        val value = raw?.trim()
            ?.replace('\u00A0', ' ')
            // Сервер пишет разряды запятой («1,234») — это не десятичный разделитель.
            ?.replace(",", "")
            ?.replace(" ", "")
            .orEmpty()
        if (value.isEmpty()) return null
        val unit = when (value.last().uppercaseChar()) {
            'K' -> "тыс."
            'M' -> "млн"
            'B' -> "млрд"
            else -> null
        }
        val numeric = if (unit != null) value.dropLast(1) else value
        val normalized = numeric.replace(',', '.')
        val number = normalized.toDoubleOrNull() ?: return null
        if (number < 0) return null
        // С сокращением единицы всегда «подписчиков»: «19,1 тыс. подписчиков».
        val word = if (unit != null) "подписчиков" else plural(number.toLong(), "подписчик", "подписчика", "подписчиков")
        val amount = if (unit != null) "${decimalText(normalized)} $unit" else groupedInteger(number.toLong())
        return "$amount $word"
    }

    /** Счётчик песен по-русски: «1 песня», «2 песни», «5 песен». */
    fun songsLabel(count: Int): String = "$count ${plural(count.toLong(), "песня", "песни", "песен")}"

    /**
     * Подзаголовок диалога: имя и, если сервер дал, подписчики. Имя не дублируется,
     * когда оно уже стоит заголовком карточки.
     */
    fun subtitle(name: String, subscribers: String?): String {
        val clean = name.trim()
        // Пустое имя — уже готовый ответ; подписчики к «неизвестен» не приписываем.
        if (clean.isEmpty()) return UNKNOWN_ARTIST
        val label = subscribersLabel(subscribers) ?: return clean
        return "$clean · $label"
    }

    /**
     * Можно ли стартовать загрузку: второй тап по имени, пока первый запрос в
     * полёте, не должен порождать второй запрос.
     */
    fun canStartRequest(inFlight: Boolean): Boolean = !inFlight

    /** Текст сбоя: «не найден» — это ответ сервера, а не сеть, и повтор не поможет. */
    fun failureText(notFound: Boolean): String = if (notFound) NOT_FOUND else NETWORK_FAILURE

    /** Повтор осмыслен только для сетевого сбоя; при 404 кнопки повтора нет. */
    fun retryAvailable(notFound: Boolean): Boolean = !notFound

    // ------------------------------------------------------------ внутреннее

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

    /** «19.1» → «19,1»; «19.0» → «19» (хвостовой ноль не печатаем). */
    private fun decimalText(normalized: String): String {
        val trimmed = normalized.trimEnd('0').trimEnd('.')
        val safe = trimmed.ifEmpty { "0" }
        return safe.replace('.', ',')
    }

    /** 1234 → «1 234»: разряды через неразрывный пробел, как в подписях счётчиков. */
    private fun groupedInteger(value: Long): String {
        val digits = value.toString()
        val builder = StringBuilder()
        digits.forEachIndexed { index, ch ->
            if (index > 0 && (digits.length - index) % 3 == 0) builder.append('\u00A0')
            builder.append(ch)
        }
        return builder.toString()
    }
}
