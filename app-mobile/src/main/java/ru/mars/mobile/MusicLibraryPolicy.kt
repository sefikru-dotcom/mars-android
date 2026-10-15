package ru.mars.mobile

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Чистая политика разделов «Плейлисты» и «История» экрана музыки: разбор серверной
 * метки `played_at` («2026-10-12 09:37:59») в epoch-millis, человекочитаемая подпись
 * («сегодня, 09:37»), русские числительные для счётчиков («1 плейлист», «3 трека»),
 * выбор состояния списка (загрузка/ошибка/пусто/контент) и перевод серверного
 * текста ошибки в понятную фразу. Без Android-импортов — тестируется на JVM.
 */
object MusicLibraryPolicy {
    /** Метка не распознана: сервер не отдал played_at или формат чужд. */
    const val UNKNOWN_PLAYED_AT = 0L

    /** Серверный формат `played_at`; наивная метка трактуется как UTC (как в ChatTime). */
    private val PLAYED_AT_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss[.SSSSSS][.SSS]]")

    private val MONTHS_GENITIVE = arrayOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )

    /** Подпись строки истории, когда метка не разобрана: строка всё равно нужна. */
    const val NO_DATE_LABEL = "дата неизвестна"

    /**
     * Разбор `played_at` в epoch-millis. Ожидается `YYYY-MM-DD HH:MM:SS` (секунды и
     * доли секунды необязательны, смещение не приходит — считаем UTC). null/пусто/
     * нераспознанное значение даёт [UNKNOWN_PLAYED_AT].
     */
    fun parsePlayedAt(raw: String?): Long {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return UNKNOWN_PLAYED_AT
        return runCatching {
            LocalDateTime.parse(value, PLAYED_AT_FORMATTER).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrDefault(UNKNOWN_PLAYED_AT)
    }

    /**
     * Человекочитаемая подпись прослушивания относительно [nowMillis]: «сегодня, 09:37»,
     * «вчера, 09:37», «12 октября, 09:37», «12 октября 2025, 09:37». Нераспознанная
     * метка даёт [NO_DATE_LABEL] — строка списка без даты не должна выглядеть сломанной.
     */
    fun playedAtLabel(raw: String?, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val millis = parsePlayedAt(raw)
        if (millis <= UNKNOWN_PLAYED_AT) return NO_DATE_LABEL
        val at = Instant.ofEpochMilli(millis).atZone(zone)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val day = at.toLocalDate()
        val clock = "%02d:%02d".format(at.hour, at.minute)
        return when (day) {
            today -> "сегодня, $clock"
            today.minusDays(1) -> "вчера, $clock"
            else -> buildString {
                append(day.dayOfMonth).append(' ').append(MONTHS_GENITIVE[day.monthValue - 1])
                if (day.year != today.year) append(' ').append(day.year)
                append(", ").append(clock)
            }
        }
    }

    /**
     * Русское числительное для числа плейлистов: «нет плейлистов», «1 плейлист»,
     * «2 плейлиста», «5 плейлистов». Счётчик — обычный текст с пробелом и числом.
     */
    fun playlistCountLabel(count: Int): String {
        val n = count.coerceAtLeast(0)
        if (n == 0) return "нет плейлистов"
        return "$n ${plural(n, "плейлист", "плейлиста", "плейлистов")}"
    }

    /** Русское числительное для числа треков: «нет треков», «1 трек», «2 трека», «5 треков». */
    fun trackCountLabel(count: Int): String {
        val n = count.coerceAtLeast(0)
        if (n == 0) return "нет треков"
        return "$n ${plural(n, "трек", "трека", "треков")}"
    }

    /**
     * Подпись строки плейлиста: название плюс число треков («Street · 1 трек»).
     * Пустое название не оставляет висеть разделитель.
     */
    fun playlistSubtitle(trackCount: Int, createdAt: String?): String = buildList {
        add(trackCountLabel(trackCount))
        createdAt?.trim()?.takeIf { it.isNotEmpty() }?.let { add("создан $it") }
    }.joinToString(" · ")

    /**
     * Состояние списка раздела. Ошибка важнее пустоты (не показываем «пусто» вместо
     * сбоя), загрузка важнее контента (пока идёт запрос, старые строки не считаются).
     */
    fun state(isLoading: Boolean, error: String?, itemCount: Int): LibraryUiState = when {
        isLoading -> LibraryUiState.Loading
        !error.isNullOrBlank() -> LibraryUiState.Error(error)
        itemCount > 0 -> LibraryUiState.Content
        else -> LibraryUiState.Empty
    }

    /**
     * Текст серверной ошибки для человека. «Title required» — это отказ по пустому
     * названию, и пользователю нужна подсказка, а не английская строка сервиса;
     * «Missing id» — служебный отказ записи истории; остальное отдаём как есть,
     * но пустой ответ не оставляем без пояснения.
     */
    fun createPlaylistError(serverError: String?): String = when (serverError?.trim()) {
        null, "" -> "Не удалось создать плейлист — проверьте сеть"
        "Title required" -> "Укажите название плейлиста"
        else -> serverError.trim()
    }

    /**
     * Текст серверной ошибки записи в плейлист. Сервис отвечает служебной строкой
     * («Title required», «Missing id») — пользователю нужна русская фраза, а не код.
     * Неизвестный ответ отдаём как есть, пустой заменяем на [fallback].
     */
    fun playlistWriteError(serverError: String?, fallback: String): String =
        serverError?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

    /** Русские формы числительного по правилам 1 / 2–4 / 0 и 5+. */
    private fun plural(n: Int, one: String, few: String, many: String): String {
        val mod100 = n % 100
        if (mod100 in 11..14) return many
        return when (n % 10) {
            1 -> one
            2, 3, 4 -> few
            else -> many
        }
    }
}

/**
 * Чистая политика записи прослушивания: Mars Connect повторяет кадр о текущем треке на
 * каждое изменение состояния проигрывателя (пауза, воспроизведение, ход), поэтому
 * запись в историю нельзя вызывать вслепую — иначе один трек попадёт туда десятки раз.
 * Решение принимается сравнением id текущего трека с id последней записи.
 */
object HistoryRecordPolicy {
    /**
     * Нужно ли писать трек [trackId] в историю, если последним записан [lastRecordedId].
     * Пустой id означает «трека нет» — писать нечего; тот же id — повтор кадра.
     */
    fun shouldRecord(trackId: String?, lastRecordedId: String?): Boolean =
        !trackId.isNullOrBlank() && trackId != lastRecordedId
}

/** Что показывает список раздела прямо сейчас. */
sealed interface LibraryUiState {
    /** Запрос в полёте: показывается плейсхолдер, старые строки не остаются. */
    data object Loading : LibraryUiState

    /** Запрос завершился отказом: [message] — уже человеческий текст. */
    data class Error(val message: String) : LibraryUiState

    /** Сервер ответил, но список пуст (у раздела своя фраза пустоты). */
    data object Empty : LibraryUiState

    /** Есть что показать. */
    data object Content : LibraryUiState
}

/**
 * Чистая политика перевода текста песни: подпись кнопки переключения (что произойдёт
 * по нажатию), решение, показывать ли перевод, и текст плейсхолдера, пока перевода нет.
 */
object LyricsTranslationPolicy {
    /** Кнопка предлагает перевод, когда его ещё нет. */
    const val ACTION_TRANSLATE = "Перевод"

    /** Кнопка возвращает оригинал, когда перевод уже на экране. */
    const val ACTION_ORIGINAL = "Оригинал"

    /** Подпись кнопки в панели текста по текущему состоянию. */
    fun actionLabel(showingTranslation: Boolean): String =
        if (showingTranslation) ACTION_ORIGINAL else ACTION_TRANSLATE

    /** Можно ли переключаться: сначала нужен непустой оригинал. */
    fun canTranslate(original: String?): Boolean = !original.isNullOrBlank()

    /**
     * Что показать в теле диалога: перевод, если он есть и не пуст, иначе оригинал.
     * Пустой перевод (сервис отвечает `translated:""`) не должен затирать текст.
     */
    fun bodyFor(original: String, translation: String?, showingTranslation: Boolean): String {
        val translated = translation?.takeIf { it.isNotBlank() } ?: return original
        return if (showingTranslation) translated else original
    }

    /** Фраза для пустого ответа перевода — «пусто» не выглядит как сбой. */
    fun emptyTranslationText(): String = "Перевод не получен — сервис вернул пустой ответ"

    /** Фраза для отказа перевода. */
    fun failureText(): String = "Перевод недоступен — проверьте сеть"
}
