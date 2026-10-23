package ru.mars.mobile

import android.view.View
import android.widget.TextView

/**
 * Подпись строки трека: «исполнитель · альбом · 3:24» — и её раскладка под ширину экрана.
 *
 * Вынесено из [MusicActivity] (W1, шаг 13). Раньше был `inner`-классом Activity и читал
 * `resources.displayMetrics`; плотность теперь приходит параметром конструктора —
 * логика и политика [TrackSubtitlePolicy] не менялись.
 *
 * Зачем отдельный класс, а не три строки в [trackRow]: подпись перерисовывается из
 * двух источников — ширины (после первой меры) и состояния плеера («Сейчас играет»
 * вместо длительности). Оба пути обязаны собирать текст ОДНОЙ политикой
 * ([TrackSubtitlePolicy]), иначе перекраска активного трека возвращала бы
 * длительность на место альбома или удваивала разделитель.
 *
 * Почему раскладка по ширине: на POCO F5 (411dp) под подпись остаётся ~141dp после
 * обложки и трёх кнопок. Имя альбома там не влезает рядом с длительностью, и
 * прежний `maxWidth = 140dp` выдавливал длительность за правый край строки — она
 * физически обрезалась. Теперь длительность получает бюджет первой, а альбом при
 * нехватке места уходит на собственную строку, где обрезается он сам.
 */
internal class TrackSubtitleBinder(
    private val artist: String,
    private val album: String?,
    private val duration: String,
    /**
     * Плотность экрана: `resources` принадлежат Activity, поэтому в вынесенный
     * класс она приходит значением — единственная правка сигнатуры при переносе.
     */
    private val density: Float,
    private val artistLabel: TextView,
    private val albumInline: TextView,
    private val albumSecondLine: TextView,
    private val metaView: TextView,
) {

    /** До первой меры альбом считаем «не влез»: так длительность не может обрезаться. */
    private var placement = TrackSubtitlePolicy.AlbumPlacement.SECOND_LINE
    private var measuredWidthPx = -1
    private var active = false
    private var playing = false

    /**
     * Ширина блока текста стала известна (или изменилась после поворота): выбираем
     * раскладку по измеренным ширинам сегментов и перерисовываем подпись.
     * Повторный вызов с той же шириной ничего не делает — иначе слушатель меры и
     * смена видимости зациклили бы layout.
     */
    fun onAvailableWidth(widthPx: Int) {
        if (album == null || widthPx <= 0 || widthPx == measuredWidthPx) return
        measuredWidthPx = widthPx
        placement = TrackSubtitlePolicy.albumPlacement(
            availableDp = widthPx / density,
            albumSegmentDp = albumInline.paint.measureText(
                TrackSubtitlePolicy.albumSegment(artist, album),
            ) / density,
            tailSegmentDp = metaView.paint.measureText(
                TrackSubtitlePolicy.tailSegment(artist, album, tailText()),
            ) / density,
        )
        render()
    }

    /** Перекраска под состояние плеера: активный играющий трек показывает статус вместо длительности. */
    fun render(active: Boolean, playing: Boolean) {
        this.active = active
        this.playing = playing
        render()
    }

    private fun tailText(): String =
        if (active && playing) TrackSubtitlePolicy.NOW_PLAYING else duration

    private fun render() {
        val tail = tailText()
        artistLabel.text = artist
        if (album == null) {
            albumInline.visibility = View.GONE
            albumSecondLine.visibility = View.GONE
            metaView.text = TrackSubtitlePolicy.tailSegment(artist, null, tail)
            return
        }
        when (placement) {
            TrackSubtitlePolicy.AlbumPlacement.INLINE -> {
                albumInline.text = TrackSubtitlePolicy.albumSegment(artist, album)
                albumInline.contentDescription = album
                albumInline.visibility = View.VISIBLE
                albumSecondLine.visibility = View.GONE
                metaView.text = TrackSubtitlePolicy.tailSegment(artist, album, tail)
            }
            TrackSubtitlePolicy.AlbumPlacement.SECOND_LINE -> {
                albumInline.visibility = View.GONE
                albumSecondLine.text = album
                albumSecondLine.contentDescription = album
                albumSecondLine.visibility = View.VISIBLE
                metaView.text = TrackSubtitlePolicy.tailSegment(artist, null, tail)
            }
        }
    }
}
