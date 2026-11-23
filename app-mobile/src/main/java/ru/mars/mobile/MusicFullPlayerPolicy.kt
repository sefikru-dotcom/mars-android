package ru.mars.mobile

import ru.mars.network.model.MusicTrack

/**
 * Чистые решения раскрытого плеера: что писать в заголовке, когда перезагружать обложку,
 * в каком состоянии сердечко и как озвучиваются его поверхности. Без Android-импортов —
 * проверяется JVM-тестами ([MusicFullPlayerPolicyTest]).
 *
 * Зачем политика: в веб-версии раскрытый плеер обновлял метаданные ТОЛЬКО при открытии,
 * и при автопереходе на следующий трек на большом экране оставались название, артист и
 * обложка предыдущего. Здесь закреплено правило «заголовок всегда из текущего трека», а
 * решение «перезагружать ли картинку» вынесено отдельно, чтобы смена трека не дёргала
 * загрузчик, когда обложка та же (один альбом), и обязательно дёргала, когда другая.
 */
object MusicFullPlayerPolicy {

    /** Заголовок пустого плеера — тот же, что у нижней полосы. */
    const val EMPTY_TITLE = "Ничего не играет"

    /** Подпись под пустым заголовком — та же, что у нижней полосы. */
    const val EMPTY_ARTIST = "Mars Audio"

    /** Действие кнопки «вниз»: закрывает раскрытый плеер. */
    const val ACTION_CLOSE = "Свернуть плеер"

    /**
     * Действие тапа по обложке/названию в нижней полосе: разворачивает плеер на весь экран.
     *
     * Подпись живёт в политике, а не в слое вью: это единственный фокус-стоп, который
     * для TalkBack открывает раскрытый плеер, и он обязан называть действие, а не объект.
     */
    const val ACTION_OPEN = "Развернуть плеер"

    /**
     * Подпись обложки/названия в нижней полосе: действие вместе с названием трека.
     *
     * Статичное «Развернуть плеер» на узле, который читает TalkBack, теряло бы имя трека —
     * а это ровно та информация, за которой пользователь туда смотрит. Пустой плеер
     * остаётся с чистым действием.
     */
    fun openAction(track: MusicTrack?): String {
        val name = track?.title?.trim().orEmpty()
        return if (name.isEmpty()) ACTION_OPEN else "$ACTION_OPEN: $name"
    }

    /** Действие тапа по большой обложке: показывает текст песни. */
    const val ACTION_LYRICS = "Текст песни"

    /** Заголовок раскрытого плеера: название текущего трека или пустое состояние. */
    fun title(track: MusicTrack?): String = track?.title?.takeIf { it.isNotBlank() } ?: EMPTY_TITLE

    /**
     * Подпись исполнителя. Альбом дописывается тем же правилом, что в нижней полосе
     * ([TrackSubtitlePolicy.subtitle]), поэтому две поверхности не расходятся.
     */
    fun artist(track: MusicTrack?): String =
        track?.let { TrackSubtitlePolicy.subtitle(it.artist, it.albumTitle, null) } ?: EMPTY_ARTIST

    /** URL обложки раскрытого плеера; null/пробелы — обложки нет. */
    fun artworkUrl(track: MusicTrack?): String? =
        track?.thumbnail?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Нужно ли перезагружать обложку: только при РЕАЛЬНОЙ смене URL.
     *
     * Иначе каждый кадр прогресса (или pause/resume того же трека) заново дёргал бы
     * `ImageLoader.load`, а без этой проверки — наоборот, при смене трека внутри одного
     * альбома картинка осталась бы от предыдущего, если бы сравнивали объекты трека.
     */
    fun shouldReloadArtwork(previousUrl: String?, nextUrl: String?): Boolean = previousUrl != nextUrl

    /**
     * Подпись обложки для TalkBack: тап по ней открывает текст песни, поэтому действие
     * называется вместе с треком — иначе на экране с одной большой картинкой непонятно,
     * к чему относится кнопка.
     */
    fun artworkAction(track: MusicTrack?): String {
        val name = track?.title?.trim().orEmpty()
        return if (name.isEmpty()) ACTION_LYRICS else "$ACTION_LYRICS: $name"
    }

    /**
     * Состояние сердечка: заполнено ли, какой подписью озвучивается и что сообщает
     * TalkBack как состояние.
     *
     * Подпись берётся из общей [MusicA11yPolicy] (как в строках трека), `selected` —
     * для платформенного объявления состояния выбранности. [busy] (запрос в полёте)
     * гасит кнопку, но НЕ меняет подпись: состояние ещё не изменилось, а врать
     * TalkBack'у о действии нельзя.
     */
    data class HeartState(
        val filled: Boolean,
        val description: String,
        val selected: Boolean,
        val enabled: Boolean,
    )

    fun heart(trackId: String?, favoriteIds: Set<String>, busy: Boolean): HeartState {
        val filled = trackId != null && favoriteIds.contains(trackId)
        return HeartState(
            filled = filled,
            description = MusicA11yPolicy.heartAction(filled),
            selected = filled,
            enabled = trackId != null && !busy,
        )
    }
}
