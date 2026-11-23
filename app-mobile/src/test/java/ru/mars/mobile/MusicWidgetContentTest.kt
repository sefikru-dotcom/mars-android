package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.mobile.service.MusicNotificationPolicy
import ru.mars.mobile.widget.MusicWidgetContent

/**
 * Чистая логика содержимого виджета «Музыка Марса».
 *
 * Виджет рисует RemoteViews, который на JVM не поднять, поэтому проверяется ровно то,
 * что решается ДО Android-слоя: тексты с откатами, action-строка для кнопки, подпись
 * TalkBack, резолв URL обложки и степень даунсэмплинга. Проводка (PendingIntent,
 * ресивер, layout) закреплена отдельно — [MusicWidgetContractTest].
 */
class MusicWidgetContentTest {

    // ------------------------------------------------------------------ title

    @Test
    fun `название трека отдаётся как есть, с обрезкой пробелов`() {
        assertEquals("Кино", MusicWidgetContent.title("  Кино  ", hasTrack = true))
    }

    @Test
    fun `без трека заголовок называет приложение, а не пустоту`() {
        assertEquals(MusicWidgetContent.NO_TRACK_TITLE, MusicWidgetContent.title(null, hasTrack = false))
        assertEquals(MusicWidgetContent.NO_TRACK_TITLE, MusicWidgetContent.title("", hasTrack = false))
        assertEquals(MusicWidgetContent.NO_TRACK_TITLE, MusicWidgetContent.title("   ", hasTrack = false))
    }

    @Test
    fun `трек с пустым названием — неизвестный трек, а не нет трека`() {
        assertEquals(MusicWidgetContent.UNKNOWN_TITLE, MusicWidgetContent.title(null, hasTrack = true))
        assertEquals(MusicWidgetContent.UNKNOWN_TITLE, MusicWidgetContent.title("", hasTrack = true))
        assertEquals(MusicWidgetContent.UNKNOWN_TITLE, MusicWidgetContent.title("  ", hasTrack = true))
    }

    // --------------------------------------------------------------- subtitle

    @Test
    fun `исполнитель отдаётся как есть, с обрезкой пробелов`() {
        assertEquals("Виктор Цой", MusicWidgetContent.subtitle(" Виктор Цой ", hasTrack = true))
    }

    @Test
    fun `пустой исполнитель у живого трека не выдаётся за отсутствие трека`() {
        assertEquals(MusicWidgetContent.UNKNOWN_ARTIST, MusicWidgetContent.subtitle(null, hasTrack = true))
        assertEquals(MusicWidgetContent.UNKNOWN_ARTIST, MusicWidgetContent.subtitle("  ", hasTrack = true))
    }

    @Test
    fun `в простое подзаголовок говорит, что ничего не играет`() {
        assertEquals(MusicWidgetContent.NO_TRACK_SUBTITLE, MusicWidgetContent.subtitle(null, hasTrack = false))
        assertEquals(MusicWidgetContent.NO_TRACK_SUBTITLE, MusicWidgetContent.subtitle("", hasTrack = false))
    }

    // ------------------------------------------------------------- transport

    @Test
    fun `играет — кнопка шлёт паузу, стоит — воспроизведение`() {
        assertEquals(MusicNotificationPolicy.ACTION_PAUSE, MusicWidgetContent.playPauseAction(true))
        assertEquals(MusicNotificationPolicy.ACTION_PLAY, MusicWidgetContent.playPauseAction(false))
    }

    /**
     * Строки берутся из [MusicNotificationPolicy], а не дублируются литералами: кнопка
     * виджета обязана попасть в тот же `onStartCommand`, что и кнопка шторки. Если
     * когда-нибудь строку поменяют в policy, тест поймает расхождение здесь.
     */
    @Test
    fun `action-строки виджета совпадают со строками сервиса дословно`() {
        assertEquals("ru.mars.mobile.service.action.PLAY", MusicWidgetContent.playPauseAction(false))
        assertEquals("ru.mars.mobile.service.action.PAUSE", MusicWidgetContent.playPauseAction(true))
        // Справочник строк — MusicNotificationPolicy; виджет обязан брать их оттуда.
        assertEquals(MusicNotificationPolicy.ACTION_PLAY, MusicWidgetContent.playPauseAction(false))
        assertEquals(MusicNotificationPolicy.ACTION_PAUSE, MusicWidgetContent.playPauseAction(true))
        assertEquals("ru.mars.mobile.service.action.PREV", MusicNotificationPolicy.ACTION_PREV)
        assertEquals("ru.mars.mobile.service.action.NEXT", MusicNotificationPolicy.ACTION_NEXT)
    }

    @Test
    fun `подпись TalkBack читает действие кнопки, как на экране музыки`() {
        assertEquals(MusicA11yPolicy.ACTION_PAUSE, MusicWidgetContent.playPauseDescription(true))
        assertEquals(MusicA11yPolicy.ACTION_PLAY, MusicWidgetContent.playPauseDescription(false))
        assertEquals("Пауза", MusicWidgetContent.playPauseDescription(true))
        assertEquals("Воспроизвести", MusicWidgetContent.playPauseDescription(false))
    }

    // ------------------------------------------------------------------ cover

    @Test
    fun `относительный путь обложки резолвится в абсолютный`() {
        assertEquals(
            "https://marscore.ru/music/api/thumb?v=abc",
            MusicWidgetContent.coverUrl("/music/api/thumb?v=abc"),
        )
    }

    @Test
    fun `абсолютный url обложки сохраняется`() {
        assertEquals(
            "https://cdn.example/cover.jpg",
            MusicWidgetContent.coverUrl("https://cdn.example/cover.jpg"),
        )
    }

    @Test
    fun `нет обложки — null, посторонние схемы отбрасываются`() {
        assertNull(MusicWidgetContent.coverUrl(null))
        assertNull(MusicWidgetContent.coverUrl(""))
        assertNull(MusicWidgetContent.coverUrl("   "))
        assertNull(MusicWidgetContent.coverUrl("javascript:alert(1)"))
        assertNull(MusicWidgetContent.coverUrl("data:image/png;base64,AAAA"))
    }

    // -------------------------------------------------------------- downsample

    @Test
    fun `большая обложка уменьшается кратно степени двойки до цели`() {
        // 1024 -> 512 -> 256: шаг 4 (1024/4=256 ≥ 192), шаг 8 уже дал бы 128 < 192.
        assertEquals(4, MusicWidgetContent.inSampleSizeFor(1024, 1024, 192))
    }

    @Test
    fun `квадратная обложка чуть больше цели не уменьшается`() {
        assertEquals(1, MusicWidgetContent.inSampleSizeFor(192, 192, 192))
        assertEquals(1, MusicWidgetContent.inSampleSizeFor(100, 100, 192))
    }

    @Test
    fun `уменьшение считается по меньшей стороне, чтобы не обрезать кадр`() {
        // Высота 120 не даёт шага 2: 120/2=60 < 192.
        assertEquals(1, MusicWidgetContent.inSampleSizeFor(2000, 120, 192))
        // 800x600: шаг 2 даёт 400x300, шаг 4 — 200x150 (150 < 192), значит 2.
        assertEquals(2, MusicWidgetContent.inSampleSizeFor(800, 600, 192))
    }

    @Test
    fun `битый заголовок картинки не роняет расчёт`() {
        assertEquals(1, MusicWidgetContent.inSampleSizeFor(0, 0, 192))
        assertEquals(1, MusicWidgetContent.inSampleSizeFor(-5, 100, 192))
        assertEquals(1, MusicWidgetContent.inSampleSizeFor(100, 100, 0))
    }

    @Test
    fun `потолок скачивания обложки положителен и не превышает пары мегабайт`() {
        assertTrue(
            "потолок ${MusicWidgetContent.COVER_MAX_BYTES} вне разумного диапазона",
            MusicWidgetContent.COVER_MAX_BYTES in 1..2_000_000,
        )
        assertTrue("цель обложки не положительна", MusicWidgetContent.COVER_TARGET_PX > 0)
    }
}
