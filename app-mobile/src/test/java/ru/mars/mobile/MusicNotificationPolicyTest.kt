package ru.mars.mobile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import ru.mars.mobile.service.MusicNotificationPolicy
import ru.mars.mobile.service.MusicNotificationPolicy.ActionType

/**
 * JVM-тесты чистой политики медиа-нотификации [MusicNotificationPolicy]: видимость и
 * порядок транспортных кнопок, intent-action строки, compact-индексы, правило «живого»
 * воспроизведения, роутинг action → type, арифметика перемотки и обновления позиции,
 * ключ обложки в шторке, флаги intent'а тапа.
 *
 * Второй блок — контракт по РЕАЛЬНОМУ исходнику сервиса (паттерн
 * [MusicA11yContractTest]): проводка MediaSession-перемотки, обложки в шторке и
 * перехода в приложение по тапу закреплена так, что удаление любой из трёх частей
 * ломает тест.
 */
class MusicNotificationPolicyTest {

    // ---------------------------------------------------------------- канал и id

    @Test
    fun `channel id is mars_music_playback`() {
        assertEquals("mars_music_playback", MusicNotificationPolicy.CHANNEL_ID)
    }

    @Test
    fun `notification id is stable and non-zero`() {
        assertTrue(MusicNotificationPolicy.NOTIFICATION_ID != 0)
        assertEquals(
            MusicNotificationPolicy.NOTIFICATION_ID,
            MusicNotificationPolicy.NOTIFICATION_ID
        )
    }

    // ---------------------------------------------------------------- транспорт

    @Test
    fun `playing state shows prev pause next stop in shade order`() {
        val actions = MusicNotificationPolicy.actions(isPlaying = true, hasTrack = true)
        assertEquals(
            listOf(ActionType.PREV, ActionType.PAUSE, ActionType.NEXT, ActionType.STOP),
            actions.map { it.type }
        )
    }

    @Test
    fun `paused state shows play instead of pause, same positions`() {
        val actions = MusicNotificationPolicy.actions(isPlaying = false, hasTrack = true)
        assertEquals(
            listOf(ActionType.PREV, ActionType.PLAY, ActionType.NEXT, ActionType.STOP),
            actions.map { it.type }
        )
    }

    @Test
    fun `play and pause are mutually exclusive`() {
        for (playing in listOf(true, false)) {
            val types = MusicNotificationPolicy.actions(playing, true).map { it.type }
            assertTrue(types.contains(ActionType.PAUSE) != types.contains(ActionType.PLAY))
            assertEquals(if (playing) ActionType.PAUSE else ActionType.PLAY, types[1])
        }
    }

    /**
     * Пять действий — потолок для строки уведомления, шестое система молча отбросит.
     * Поэтому перемотка живёт в действиях MediaSession, а не в списке кнопок.
     */
    @Test
    fun `transport list never exceeds the five actions a notification row allows`() {
        for (playing in listOf(true, false)) {
            val actions = MusicNotificationPolicy.actions(playing, true)
            assertEquals(4, actions.size)
            assertTrue(actions.size <= 5)
        }
    }

    @Test
    fun `no track hides all actions`() {
        assertTrue(MusicNotificationPolicy.actions(isPlaying = true, hasTrack = false).isEmpty())
        assertTrue(MusicNotificationPolicy.actions(isPlaying = false, hasTrack = false).isEmpty())
    }

    @Test
    fun `every action carries its stable intent string and a content description`() {
        val expected = mapOf(
            ActionType.PLAY to MusicNotificationPolicy.ACTION_PLAY,
            ActionType.PAUSE to MusicNotificationPolicy.ACTION_PAUSE,
            ActionType.NEXT to MusicNotificationPolicy.ACTION_NEXT,
            ActionType.PREV to MusicNotificationPolicy.ACTION_PREV,
            ActionType.STOP to MusicNotificationPolicy.ACTION_STOP,
        )
        val actions = MusicNotificationPolicy.actions(isPlaying = true, hasTrack = true) +
            MusicNotificationPolicy.actions(isPlaying = false, hasTrack = true)
        actions.forEach { action ->
            assertEquals(expected[action.type], action.action)
            assertTrue(action.contentDescription.isNotBlank())
        }
        // Все пять action-строк различны — иначе PendingIntent'ы схлопнутся в один.
        assertEquals(5, expected.values.toSet().size)
    }

    @Test
    fun `typeOf routes every action string back to its type`() {
        assertEquals(ActionType.PLAY, MusicNotificationPolicy.typeOf(MusicNotificationPolicy.ACTION_PLAY))
        assertEquals(ActionType.PAUSE, MusicNotificationPolicy.typeOf(MusicNotificationPolicy.ACTION_PAUSE))
        assertEquals(ActionType.NEXT, MusicNotificationPolicy.typeOf(MusicNotificationPolicy.ACTION_NEXT))
        assertEquals(ActionType.PREV, MusicNotificationPolicy.typeOf(MusicNotificationPolicy.ACTION_PREV))
        assertEquals(ActionType.STOP, MusicNotificationPolicy.typeOf(MusicNotificationPolicy.ACTION_STOP))
    }

    @Test
    fun `typeOf returns null for unknown or absent actions`() {
        assertNull(MusicNotificationPolicy.typeOf(null))
        assertNull(MusicNotificationPolicy.typeOf(""))
        assertNull(MusicNotificationPolicy.typeOf("ru.mars.mobile.service.action.NUKE"))
    }

    // ---------------------------------------------------------------- compact

    @Test
    fun `compact view shows prev toggle next but never stop`() {
        for (playing in listOf(true, false)) {
            val actions = MusicNotificationPolicy.actions(playing, true)
            val compact = MusicNotificationPolicy.compactActionIndices(playing, true)
            assertArrayEquals(intArrayOf(0, 1, 2), compact)
            compact.forEach { index ->
                assertNotEquals(ActionType.STOP, actions[index].type)
                assertTrue(index in actions.indices)
            }
        }
        // Без трека кнопок нет — compact пустой, MediaStyle не должен падать.
        assertEquals(0, MusicNotificationPolicy.compactActionIndices(false, false).size)
        assertEquals(0, MusicNotificationPolicy.compactActionIndices(true, false).size)
    }

    @Test
    fun `playback is alive while a track is loaded or audio plays`() {
        assertTrue(MusicNotificationPolicy.isPlaybackAlive(hasTrack = true, isPlaying = true))
        assertTrue(MusicNotificationPolicy.isPlaybackAlive(hasTrack = true, isPlaying = false))
        assertTrue(MusicNotificationPolicy.isPlaybackAlive(hasTrack = false, isPlaying = true))
        assertFalse(MusicNotificationPolicy.isPlaybackAlive(hasTrack = false, isPlaying = false))
    }

    // ---------------------------------------------------------------- перемотка

    @Test
    fun `seek step is ten seconds`() {
        assertEquals(10_000L, MusicNotificationPolicy.SEEK_STEP_MS)
        // Шаг должен быть положительным и кратным секунде — иначе rewind/fast-forward
        // поедет в другую сторону или потеряет точность.
        assertTrue(MusicNotificationPolicy.SEEK_STEP_MS > 0)
        assertEquals(0L, MusicNotificationPolicy.SEEK_STEP_MS % 1_000L)
    }

    @Test
    fun `seekTarget moves forward and back by one step`() {
        val duration = 180_000
        val step = MusicNotificationPolicy.SEEK_STEP_MS
        assertEquals(40_000, MusicNotificationPolicy.seekTarget(30_000, step, duration))
        assertEquals(20_000, MusicNotificationPolicy.seekTarget(30_000, -step, duration))
    }

    @Test
    fun `seekTarget clamps at the start of the track`() {
        val step = MusicNotificationPolicy.SEEK_STEP_MS
        // Меньше шага до нуля — не уходим в минус.
        assertEquals(0, MusicNotificationPolicy.seekTarget(3_000, -step, 180_000))
        assertEquals(0, MusicNotificationPolicy.seekTarget(0, -step, 180_000))
        // Мусорный отрицательный вход не превращается в отрицательную цель.
        assertEquals(0, MusicNotificationPolicy.seekTarget(-5_000, -step, 180_000))
        assertEquals(5_000, MusicNotificationPolicy.seekTarget(-5_000, step, 180_000))
    }

    @Test
    fun `seekTarget clamps at the end of the track when duration is known`() {
        val step = MusicNotificationPolicy.SEEK_STEP_MS
        assertEquals(180_000, MusicNotificationPolicy.seekTarget(175_000, step, 180_000))
        assertEquals(180_000, MusicNotificationPolicy.seekTarget(180_000, step, 180_000))
    }

    @Test
    fun `seekTarget leaves the end open while duration is unknown`() {
        // duration == 0 — движок ещё не отдал длительность; верхний кламп не наш.
        val step = MusicNotificationPolicy.SEEK_STEP_MS
        assertEquals(15_000, MusicNotificationPolicy.seekTarget(5_000, step, 0))
        assertEquals(0, MusicNotificationPolicy.seekTarget(5_000, -step, 0))
        // Отрицательная длительность трактуется так же, как неизвестная.
        assertEquals(15_000, MusicNotificationPolicy.seekTarget(5_000, step, -1))
    }

    @Test
    fun `seekTarget never wraps near Int MAX`() {
        val nearMax = Int.MAX_VALUE - 5_000
        val target = MusicNotificationPolicy.seekTarget(
            nearMax, MusicNotificationPolicy.SEEK_STEP_MS, nearMax
        )
        assertEquals(nearMax, target)
        assertTrue(target >= 0)
        // Без известной длительности — тоже без переполнения в минус.
        assertTrue(
            MusicNotificationPolicy.seekTarget(
                Int.MAX_VALUE - 1, MusicNotificationPolicy.SEEK_STEP_MS, 0
            ) > 0
        )
    }

    @Test
    fun `clampPosition pins an absolute seek into the track`() {
        assertEquals(90_000, MusicNotificationPolicy.clampPosition(90_000L, 180_000))
        assertEquals(0, MusicNotificationPolicy.clampPosition(-1L, 180_000))
        assertEquals(180_000, MusicNotificationPolicy.clampPosition(999_999L, 180_000))
        // Неизвестная длительность: отрицательное всё равно не пропускаем.
        assertEquals(0, MusicNotificationPolicy.clampPosition(-1L, 0))
        assertEquals(42_000, MusicNotificationPolicy.clampPosition(42_000L, 0))
        // Беззнаковый мусор за пределами Int не заворачивается в минус.
        assertEquals(Int.MAX_VALUE, MusicNotificationPolicy.clampPosition(Long.MAX_VALUE, 0))
    }

    @Test
    fun `session position refreshes only after a one second jump`() {
        assertTrue(MusicNotificationPolicy.shouldRefreshSessionPosition(0, 1_000))
        assertTrue(MusicNotificationPolicy.shouldRefreshSessionPosition(0, 5_000))
        // Обычный тик прогресса мельче секунды не должен переписывать PlaybackState.
        assertFalse(MusicNotificationPolicy.shouldRefreshSessionPosition(0, 200))
        assertFalse(MusicNotificationPolicy.shouldRefreshSessionPosition(4_000, 4_000))
        // Перемотка назад — тоже прыжок, позиция обязана обновиться.
        assertTrue(MusicNotificationPolicy.shouldRefreshSessionPosition(90_000, 80_000))
        assertTrue(MusicNotificationPolicy.shouldRefreshSessionPosition(80_000, 90_000))
    }

    // ---------------------------------------------------------------- обложка

    @Test
    fun `artwork key ignores blank and missing thumbnails`() {
        assertNull(MusicNotificationPolicy.artworkKey(null))
        assertNull(MusicNotificationPolicy.artworkKey(""))
        assertNull(MusicNotificationPolicy.artworkKey("   "))
    }

    @Test
    fun `artwork key is the trimmed url so the same track does not reload`() {
        assertEquals(
            "https://marscore.ru/music/api/thumb?v=abc",
            MusicNotificationPolicy.artworkKey("  https://marscore.ru/music/api/thumb?v=abc ")
        )
    }

    @Test
    fun `artwork reloads only when the key changes`() {
        val url = "https://marscore.ru/music/api/thumb?v=abc"
        // Первый трек: ключа ещё нет — грузим.
        assertTrue(MusicNotificationPolicy.needsArtworkLoad(null, url))
        // Тот же трек (повторный onStateChanged) — не грузим.
        assertFalse(MusicNotificationPolicy.needsArtworkLoad(url, url))
        assertFalse(MusicNotificationPolicy.needsArtworkLoad(url, "  $url  "))
        // Пустой thumbnail не сбрасывает уже показанную обложку.
        assertFalse(MusicNotificationPolicy.needsArtworkLoad(url, "  "))
        assertFalse(MusicNotificationPolicy.needsArtworkLoad(url, null))
        // Другой трек — грузим.
        assertTrue(
            MusicNotificationPolicy.needsArtworkLoad(
                url, "https://marscore.ru/music/api/thumb?v=zzz"
            )
        )
        // Трек без обложки после трека с обложкой: грузить нечего, прежнюю не трогаем.
        assertFalse(MusicNotificationPolicy.needsArtworkLoad(url, null))
        assertFalse(MusicNotificationPolicy.needsArtworkLoad(null, null))
    }

    @Test
    fun `artwork target is a sane decode size`() {
        assertTrue(MusicNotificationPolicy.ARTWORK_MAX_PX >= 128)
        assertTrue(MusicNotificationPolicy.ARTWORK_MAX_PX <= 1024)
    }

    // ---------------------------------------------------------------- тап

    @Test
    fun `tap intent flags bring the existing player forward`() {
        val flags = MusicNotificationPolicy.TAP_INTENT_FLAGS
        assertTrue("нужен NEW_TASK — intent стартует вне Activity", flags and FLAG_NEW_TASK != 0)
        assertTrue("нужен CLEAR_TOP — не плодим копии стека", flags and FLAG_CLEAR_TOP != 0)
        assertEquals(
            "нужен SINGLE_TOP — повторный тап переиспользует экран",
            FLAG_SINGLE_TOP,
            flags and FLAG_SINGLE_TOP
        )
    }

    // ---------------------------------------------------------------- контракт исходника

    private val serviceSource: String by lazy {
        source("app-mobile/src/main/java/ru/mars/mobile/service/MarsMusicService.kt")
    }

    private fun source(relative: String): String {
        // Unit-тесты Gradle работают из каталога модуля app-mobile; подстраховка —
        // подъём вверх до корня репозитория (worktree/CI могут ставить другой cwd).
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            val nested = File(dir, "app-mobile/$relative")
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile
        }
        throw AssertionError("Не найден исходник: $relative")
    }

    @Test
    fun `service advertises seeking on the media session`() {
        listOf(
            "PlaybackStateCompat.ACTION_SEEK_TO",
            "PlaybackStateCompat.ACTION_REWIND",
            "PlaybackStateCompat.ACTION_FAST_FORWARD",
            "override fun onSeekTo(",
            "override fun onRewind(",
            "override fun onFastForward(",
        ).forEach { needle ->
            assertTrue("В сервисе нет $needle", serviceSource.contains(needle))
        }
        // Перемотка идёт через политику, а не через магические числа по месту.
        assertTrue(serviceSource.contains("MusicNotificationPolicy.seekTarget("))
        assertTrue(serviceSource.contains("MusicNotificationPolicy.SEEK_STEP_MS"))
        assertTrue(serviceSource.contains("MusicNotificationPolicy.clampPosition("))
    }

    @Test
    fun `service seeks the real player from every session seek callback`() {
        // Каждый колбэк обязан довести позицию до MarsMusicPlayer, иначе системная
        // перемотка в шторке молча ничего не сделает.
        val callbacks = serviceSource
            .substringAfter("private val sessionCallback")
            .substringBefore("override fun onCreate")
        assertTrue(callbacks.contains("override fun onSeekTo("))
        assertTrue(callbacks.contains("override fun onRewind("))
        assertTrue(callbacks.contains("override fun onFastForward("))
        assertTrue(callbacks.contains("MarsMusicPlayer.seekTo("))
        // И перерисовать уведомление/сессию, чтобы шторка показала новую позицию.
        assertTrue(callbacks.contains("refreshAfterSeek()"))
    }

    @Test
    fun `service puts the track cover into the shade and the session metadata`() {
        assertTrue(serviceSource.contains("setLargeIcon("))
        assertTrue(serviceSource.contains("METADATA_KEY_ALBUM_ART"))
        assertTrue(serviceSource.contains("METADATA_KEY_DISPLAY_ICON"))
        assertTrue(serviceSource.contains("MusicArtwork"))
        // Обложка не грузится синхронно в главном потоке сервиса.
        assertTrue(serviceSource.contains("Executor"))
        // Кэш обложки переиспользуется, а не качается заново на каждый трек.
        assertTrue(serviceSource.contains("MusicNotificationPolicy.needsArtworkLoad("))
    }

    @Test
    fun `service tap intent uses the shared flags and the existing music screen`() {
        assertTrue(serviceSource.contains("MusicNotificationPolicy.TAP_INTENT_FLAGS"))
        // Экран — типизированная ссылка MusicActivity::class.java, а не строка имени.
        assertTrue(serviceSource.contains("Intent(this, MusicActivity::class.java)"))
        assertTrue(serviceSource.contains("PendingIntent.getActivity("))
    }

    @Test
    fun `service refreshes the session position on real jumps only`() {
        assertTrue(serviceSource.contains("MusicNotificationPolicy.shouldRefreshSessionPosition("))
        // onProgress больше не пустышка: позиция сессии должна ехать за плеером.
        assertFalse(
            "onProgress обязан обновлять позицию сессии",
            serviceSource.contains("override fun onProgress(currentMs: Int, totalMs: Int) = Unit")
        )
    }

    /** Тело функции до первой закрывающей скобки (упрощённый баланс для наших методов). */
    private fun bodyOf(signature: String): String {
        val start = serviceSource.indexOf(signature)
        assertTrue("нет тела $signature", start >= 0)
        val open = serviceSource.indexOf('{', start + signature.length)
        var depth = 0
        for (i in open until serviceSource.length) {
            when (serviceSource[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return serviceSource.substring(open, i + 1)
                }
            }
        }
        throw AssertionError("тело не закрыто: $signature")
    }

    @Test
    fun `сервис не поднимает фиктивный foreground без трека`() {
        val body = bodyOf("override fun onStartCommand(")
        val promote = body.indexOf("promoteToForeground()")
        val alive = body.indexOf("isPlaybackAlive(")
        assertTrue("нет проверки isPlaybackAlive в onStartCommand", alive >= 0)
        assertTrue(
            "старый порядок поднимал FGS до проверки состояния — мигало пустое уведомление",
            promote > alive,
        )
        assertTrue(
            "пустой плеер обязан просто остановиться, не показывая уведомление",
            body.indexOf("stopSelf()") < promote,
        )
    }

    @Test
    fun `duplicate cover fetches are suppressed`() {
        val artwork = source("app-mobile/src/main/java/ru/mars/mobile/service/MusicArtwork.kt")
        assertTrue(
            "нет множества загрузок в полёте — дубли скачивают одну обложку много раз",
            artwork.contains("ConcurrentHashMap.newKeySet()"),
        )
        assertTrue(
            "второй запрос того же ключа не должен ставить задачу в очередь",
            artwork.contains("if (!inFlight.add(key)) return"),
        )
        assertTrue(
            "ключ обязан сниматься из inFlight при любом исходе загрузки",
            artwork.contains("inFlight.remove(key)"),
        )
    }

    private companion object {
        // Значения android.content.Intent.FLAG_ACTIVITY_* — литералы, чтобы политика
        // осталась без Android-типов и этот тест не тянул android.jar.
        const val FLAG_NEW_TASK = 0x10000000
        const val FLAG_CLEAR_TOP = 0x04000000
        const val FLAG_SINGLE_TOP = 0x20000000
    }
}
