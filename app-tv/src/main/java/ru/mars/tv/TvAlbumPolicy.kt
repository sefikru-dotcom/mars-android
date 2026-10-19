package ru.mars.tv

import ru.mars.network.repository.MusicAlbum
import ru.mars.network.model.MusicTrack
import ru.mars.network.repository.MusicRepository

/**
 * Чистые правила перехода «трек -> альбом» на TV — без Android и без сети
 * ([TvMusicNowPlayingSpec] — образец стиля).
 *
 * Зачем отдельная политика, а не проверки в [TvMusicActivity]: на TV нет тапа по
 * подписи, как на телефоне, поэтому решение «у этого трека вообще есть альбом, который
 * можно открыть» принимается в трёх местах сразу — при отрисовке строки, при показе
 * кнопки шапки и при обработке нажатия. Три копии одной проверки рано или поздно
 * разъедутся, и строка с мёртвой кнопкой вернётся.
 *
 * Префикс `MPRE` НЕ дублируется строкой: он берётся из
 * [MusicRepository.ALBUM_BROWSE_ID_PREFIX] — того же места, откуда его берёт
 * `MusicRepository.getAlbum`. Сервер отвечает кодом 500 на любой другой префикс,
 * поэтому такой browseId отсекается до запроса.
 */
object TvAlbumPolicy {

    /** Что играть при выборе трека внутри альбома: весь альбом с этой позиции. */
    data class PlayRequest(val tracks: List<MusicTrack>, val startIndex: Int)

    /** Непустой browseId альбома, либо null — открывать нечего. */
    fun requestTarget(rawBrowseId: String?): String? =
        rawBrowseId?.trim()?.takeIf { it.startsWith(MusicRepository.ALBUM_BROWSE_ID_PREFIX) }

    /** Есть ли у трека альбом, который реально откроется (кнопка/строка в списке). */
    fun openable(track: MusicTrack?): Boolean = requestTarget(track?.albumBrowseId) != null

    /**
     * Подпись строки трека в списке: `название — исполнитель · альбом · 3:41`.
     *
     * Альбом добавлен к прежней подписи, а не вместо неё: на TV строка шириной почти
     * во весь экран, места хватает, и имя альбома — единственная видимая подсказка,
     * что у трека есть куда перейти. Пустые сегменты пропускаются целиком, поэтому
     * трек без альбома выглядит ровно как раньше, без висящих « · ».
     */
    fun trackLabel(track: MusicTrack): String {
        val head = "${track.title} — ${track.artist}"
        val parts = buildList {
            add(head)
            track.albumTitle?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            track.duration.trim().takeIf { it.isNotEmpty() && it != "--:--" }?.let(::add)
        }
        return parts.joinToString(SEPARATOR)
    }

    /** Строка трека внутри диалога альбома: `название · 3:41`. */
    fun trackLine(track: MusicTrack): String {
        val duration = track.duration.trim().takeIf { it.isNotEmpty() && it != "--:--" }
        return if (duration == null) track.title else "${track.title}$SEPARATOR$duration"
    }

    /**
     * Подпись под названием альбома: `исполнитель · 2024 · 12 треков`. Год и число
     * треков — как их отдал сервер; чего нет, то не печатаем (никаких «null» и
     * пустых разделителей).
     */
    fun dialogMeta(album: MusicAlbum): String {
        val parts = buildList {
            album.artist.trim().takeIf { it.isNotEmpty() }?.let(::add)
            album.year?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            if (album.tracks.isNotEmpty()) add(trackCount(album.tracks.size))
        }
        return parts.joinToString(SEPARATOR)
    }

    /** «12 треков» / «1 трек» / «2 трека» — подпись счётчика без «12 трек». */
    fun trackCount(count: Int): String {
        val tail = when {
            count % 100 in 11..14 -> "треков"
            count % 10 == 1 -> "трек"
            count % 10 in 2..4 -> "трека"
            else -> "треков"
        }
        return "$count $tail"
    }

    /**
     * Текст состояния отказа. «Не найден» — это ответ сервера (альбом удалён/битый
     * browseId), повтор того же запроса ничего не изменит, поэтому кнопки «Повторить»
     * у него нет ([retryVisible]).
     */
    fun failureText(notFound: Boolean): String =
        if (notFound) NOT_FOUND else LOAD_FAILURE

    fun retryVisible(notFound: Boolean): Boolean = !notFound

    /** Пустой альбом — честное «нет треков», а не бесконечная загрузка. */
    const val EMPTY = "В этом альбоме нет треков"

    /** «Загружаю…» — состояние запроса. */
    const val LOADING = "Загружаю альбом…"

    /** Сеть/сервер: текст для пользователя (техническая деталь уходит в лог состояния). */
    const val LOAD_FAILURE = "Не удалось загрузить альбом. Проверьте сеть и повторите."

    /** Сервер не знает такого альбома. */
    const val NOT_FOUND = "Альбом не найден"

    /**
     * Запрос на воспроизведение альбома с позиции [index]: играем ВЕСЬ альбом, чтобы
     * после выбранного трека шёл следующий (mobile-parity: там тап по треку альбома
     * ставит очередь из альбома). Индекс вне диапазона — null: молча ничего не играем,
     * а не падаем и не подменяем выбор первым треком.
     */
    fun playRequest(album: MusicAlbum, index: Int): PlayRequest? {
        if (album.tracks.getOrNull(index) == null) return null
        return PlayRequest(album.tracks, index)
    }

    /** Разделитель сегментов подписи — один на все TV-поверхности. */
    const val SEPARATOR = " · "
}
