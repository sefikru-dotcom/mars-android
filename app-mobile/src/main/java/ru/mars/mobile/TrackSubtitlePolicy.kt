package ru.mars.mobile

/**
 * Раскладка подписи строки трека «исполнитель · альбом · 3:41» — чистая JVM-логика,
 * вынесенная из [MusicActivity] ради тестируемости по РЕАЛЬНЫМ размерам экрана.
 *
 * Зачем политика, а не просто `maxWidth = 140dp` (как было до неё): на POCO F5 (411dp)
 * после отступа корня (32dp), паддингов строки (20dp), обложки (44dp), трёх кнопок
 * (154dp) и паддингов блока текста (20dp) под подпись остаётся ~141dp. Альбом с
 * жёстким `maxWidth 140dp` занимает всю эту ширину целиком, исполнитель (weight = 1)
 * сжимается в ноль, а последняя вьюха строки — длительность — уезжает за границу
 * LinearLayout и обрезается. Поэтому решение «альбом в строке или на второй строке»
 * принимается по измеренным ширинам, а длительность получает бюджет ПЕРВОЙ.
 *
 * Инвариант (проверяется тестами): при [AlbumPlacement.INLINE] сумма
 * «сегмент альбома + сегмент хвоста + [MIN_ARTIST_DP]» не превышает доступной ширины —
 * то есть длительность видна целиком и горизонтальный скролл не нужен.
 *
 * Разделители собирает только эта политика ([SEPARATOR]): экран не склеивает строки
 * сам, поэтому двойных «· ·» и висящих разделителей при пустом поле не бывает.
 */
object TrackSubtitlePolicy {

    /** Разделитель сегментов подписи — один и тот же во всех местах экрана. */
    const val SEPARATOR = " · "

    /**
     * Хвост подписи активного трека вместо длительности. Живёт в политике, а не в
     * [MusicActivity], чтобы перекраска строки и сборка текста не разошлись.
     */
    const val NOW_PLAYING = "Сейчас играет"

    /**
     * Минимум, который обязан остаться имени исполнителя в INLINE-раскладке (~9 символов
     * 12sp). Уже этого подпись перестаёт читаться («не меня…»), и альбом честнее увести
     * на вторую строку, где у него есть вся ширина.
     */
    const val MIN_ARTIST_DP = 56f

    /** Где рисовать имя альбома в строке трека. */
    enum class AlbumPlacement {
        /** Альбом влезает в подпись целиком — остаётся в общей строке. */
        INLINE,

        /** В строке места нет — альбом уходит отдельной строкой под подписью. */
        SECOND_LINE,
    }

    /**
     * Решение по альбому для подписи шириной [availableDp].
     *
     * [albumSegmentDp] и [tailSegmentDp] — измеренные ширины готовых сегментов ВМЕСТЕ
     * с их разделителями ([albumSegment] / [tailSegment]): считать разделители отдельно
     * значило бы держать в политике и в вёрстке две разные арифметики.
     *
     * Хвост (длительность) вычитается первым, затем минимум исполнителя; остаток —
     * бюджет альбома. Альбом шире бюджета уходит на вторую строку, где обрезается уже
     * он сам, а не длительность.
     */
    fun albumPlacement(availableDp: Float, albumSegmentDp: Float, tailSegmentDp: Float): AlbumPlacement {
        if (availableDp <= 0f || albumSegmentDp <= 0f) return AlbumPlacement.SECOND_LINE
        val albumBudget = availableDp - tailSegmentDp - MIN_ARTIST_DP
        return if (albumSegmentDp <= albumBudget) AlbumPlacement.INLINE else AlbumPlacement.SECOND_LINE
    }

    /**
     * Сегмент альбома в общей строке: с ведущим разделителем, только если перед ним
     * реально что-то стоит (имя исполнителя). Иначе строка начиналась бы с « · ».
     */
    fun albumSegment(artist: String?, album: String): String {
        val name = album.trim()
        if (name.isEmpty()) return ""
        return if (artist.isNullOrBlank()) name else SEPARATOR + name
    }

    /**
     * Хвост подписи (длительность или статус «Сейчас играет») — с разделителем, если
     * перед ним есть хотя бы один непустой сегмент. Так «трек без альбома» даёт ровно
     * прежнюю подпись «исполнитель · 3:41», а трек без исполнителя — «3:41» без
     * висящего разделителя.
     */
    fun tailSegment(artist: String?, album: String?, tail: String?): String {
        val text = tail?.trim().orEmpty()
        if (text.isEmpty()) return ""
        val hasPrevious = !artist.isNullOrBlank() || !album.isNullOrBlank()
        return if (hasPrevious) SEPARATOR + text else text
    }

    /**
     * Полная подпись одной строкой — тот же результат, что даёт склейка сегментов на
     * экране ([albumSegment] + [tailSegment]). Используется там, где подпись не
     * кликабельна по частям: мини-плеер, тесты.
     */
    fun subtitle(artist: String?, album: String?, tail: String?): String {
        val head = artist?.trim().orEmpty()
        val albumPart = albumSegment(head, album.orEmpty())
        val tailPart = tailSegment(head, album, tail)
        return head + albumPart + tailPart
    }

    /**
     * Текст второй строки — имя альбома как есть (обрезается по ширине вьюхой).
     * Пустое имя означает «альбома нет»: второй строки не будет.
     */
    fun albumLine(album: String?): String? = album?.trim()?.takeIf { it.isNotEmpty() }
}
