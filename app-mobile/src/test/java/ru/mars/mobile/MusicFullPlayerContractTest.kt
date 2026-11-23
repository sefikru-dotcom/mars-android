package ru.mars.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт проводки раскрытого плеера в РЕАЛЬНОМ production-исходнике (паттерн
 * [MusicA11yContractTest]: инструментального слоя в проекте нет).
 *
 * Чистые решения проверяются отдельно — [MusicGesturePolicyTest] (порог, направление,
 * зоны контролов) и [MusicFullPlayerPolicyTest] (метаданные, обложка, сердечко). Здесь
 * закреплено то, что нельзя проверить на JVM, но можно прочитать в исходнике:
 *
 * - раскрытый плеер — ОВЕРЛЕЙ в этой же Activity, а не новая Activity (иначе музыка
 *   прерывается при открытии, а это ровно тот дефект, ради которого всё делается);
 * - метаданные обновляются на КАЖДЫЙ кадр состояния плеера, а не только при открытии
 *   (реальный баг веб-версии: при автопереходе оставался предыдущий трек);
 * - сердечко ходит через существующий [MusicActivity.onHeartClicked] и общий набор
 *   [MusicActivity.favoriteIds], а не заводит второй запрос/второй источник состояния;
 * - тап по обложке переиспользует [MusicActivity.onLyricsClicked], а не новый загрузчик;
 * - обе поверхности (полоса и раскрытый плеер) спрашивают смысл жеста у одной политики.
 *
 * Источники читаются по глобу `Music*.kt`, поэтому следующий вынос кода подхватится сам.
 */
class MusicFullPlayerContractTest {

    private fun source(relative: String): String {
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

    private val sourceDir: File by lazy {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app-mobile/src/main/java/ru/mars/mobile")
            if (candidate.isDirectory) return@lazy candidate
            dir = dir.parentFile
        }
        throw AssertionError("Не найден каталог исходников app-mobile")
    }

    /** Весь экран «Музыка»: хост плюс каждая `Music*.kt` (секции и политики). */
    private val musicSources: String by lazy {
        val parts = mutableListOf(source("src/main/java/ru/mars/mobile/MusicActivity.kt"))
        sourceDir.listFiles { f -> f.isFile && f.name.startsWith("Music") && f.name.endsWith(".kt") }
            ?.sortedBy { it.name }
            ?.forEach { parts += it.readText() }
        parts.joinToString("\n")
    }

    /** Тело функции по сигнатуре: до её сбалансированной закрывающей скобки. */
    private fun funBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("не найдена сигнатура: $signature", start >= 0)
        val open = src.indexOf('{', start + signature.length)
        assertTrue("нет тела у: $signature", open > start)
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("не закрыто тело: $signature")
    }

    // --- 1. раскрытый плеер — оверлей в этой же Activity ---

    @Test
    fun `full player is an overlay in the same activity`() {
        assertTrue(
            "раскрытый плеер обязан добавляться в корень текущей Activity",
            musicSources.contains("addContentView(root, ViewGroup.LayoutParams(-1, -1))"),
        )
        assertFalse(
            "раскрытый плеер не может быть отдельной Activity — запуск прервал бы воспроизведение",
            musicSources.contains("FullPlayerActivity") || musicSources.contains("FullPlayerScreen"),
        )
        assertFalse(
            "новая Activity для плеера запрещена: не должно быть startActivity на раскрытие",
            musicSources.contains("startActivity(Intent(this, MusicFullPlayer"),
        )
        assertTrue(
            "оверлей создаётся лениво при первом открытии",
            musicSources.contains("fullPlayer?.let { return it }"),
        )
    }

    @Test
    fun `back closes the overlay instead of leaving the screen`() {
        assertTrue(
            "системный «назад» обязан закрывать раскрытый плеер первым",
            musicSources.contains("onBackPressedDispatcher.addCallback(this, back)"),
        )
        val handle = funBody(musicSources, "override fun handleOnBackPressed()")
        assertTrue("handleOnBackPressed обязан сворачивать оверлей", handle.contains("closeFullPlayer()"))
    }

    // --- 2. тап по обложке — существующий диалог текста ---

    @Test
    fun `artwork tap reuses the existing lyrics dialog`() {
        assertTrue(
            "тап по большой обложке открывает существующий диалог текста, а не свой загрузчик",
            musicSources.contains("setOnClickListener { onLyricsClicked() }"),
        )
        assertTrue(
            "обложка обязана быть кликабельной и озвученной как действие",
            musicSources.contains("contentDescription = MusicFullPlayerPolicy.ACTION_LYRICS"),
        )
    }

    // --- 3. свайпы идут через одну политику ---

    @Test
    fun `bar and full player both classify swipes through the policy`() {
        assertTrue(
            "полоса открывает раскрытый плеер через политику, а не по своему порогу",
            funBody(musicSources, "internal class MusicBarGestureLayout(")
                .contains("MusicGesturePolicy.classify("),
        )
        assertTrue(
            "смысл свайпа на полосе берётся из политики",
            musicSources.contains("MusicGesturePolicy.barSwipe(classify(dx, dy))"),
        )
        assertTrue(
            "раскрытый плеер превращает свайп в действие политикой",
            musicSources.contains("MusicGesturePolicy.fullPlayerSwipe(swipe)"),
        )
        assertTrue(
            "панель полосы — свой контейнер жестов, а не обычный LinearLayout с OnTouchListener",
            musicSources.contains("val playerPanel = MusicBarGestureLayout(this)"),
        )
        assertFalse(
            "OnTouchListener на панели не увидит касаний, начавшихся на кликабельных детях",
            musicSources.contains("panel.setOnTouchListener"),
        )
    }

    @Test
    fun `the open target is the artwork and the title, not the whole bar`() {
        val attach = funBody(musicSources, "internal fun MusicActivity.attachBarGesture(")
        assertTrue(
            "обложка открывает раскрытый плеер с подписью действия",
            attach.contains("artwork.contentDescription = MusicFullPlayerPolicy.ACTION_OPEN"),
        )
        assertTrue(
            "название получает обработчик открытия",
            attach.contains("title.setOnClickListener { openFullPlayer() }"),
        )
        assertTrue(
            "подпись названия обновляется вместе с треком",
            funBody(musicSources, "internal fun MusicActivity.renderBarTitleTarget(")
                .contains("MusicFullPlayerPolicy.openAction(track)"),
        )
    }

    @Test
    fun `gesture never starts on a control`() {
        assertTrue(
            "касание, начавшееся на контроле, обязано оставаться контролу (перемотка != перелистывание)",
            musicSources.contains("MusicGesturePolicy.startsOnControl("),
        )
        val begin = funBody(musicSources, "private fun begin(ev: MotionEvent)")
        assertTrue(
            "свайп не начинается с контрола — проверка в самом старте жеста",
            begin.contains("startedOnControl"),
        )
        assertTrue(
            "полоса передаёт зоны контролов явно: перемотка и транспорт не открывают плеер",
            musicSources.contains("listOf(seek, radioBtn, lyricsBtn, shuffleBtn, prevBtn, playButton, nextBtn, repeatBtn)"),
        )
    }

    // --- 4. сердечко: существующий API и существующий источник состояния ---

    @Test
    fun `full player heart reuses the existing favorite path`() {
        val click = funBody(musicSources, "internal fun MusicActivity.onFullHeartClicked(")
        assertTrue(
            "сердечко раскрытого плеера обязано идти через существующий onHeartClicked",
            click.contains("onHeartClicked(track, button)"),
        )
        assertTrue(
            "состояние сердечка берётся из общего набора favoriteIds, а не из второго источника",
            funBody(musicSources, "internal fun MusicActivity.updateFullPlayer()")
                .contains("MusicFullPlayerPolicy.heart(track?.id, favoriteIds"),
        )
        assertTrue(
            "сердечко регистрируется в общем реестре, иначе лайк не перекрасит его",
            musicSources.contains("heartButtons.getOrPut(id)"),
        )
        assertTrue(
            "кнопка сообщает TalkBack состояние выбранности",
            musicSources.contains("h.heart.isSelected = state.selected"),
        )
    }

    @Test
    fun `heart is not re-enabled while a request is in flight`() {
        val update = funBody(musicSources, "internal fun MusicActivity.updateFullPlayer()")
        assertFalse(
            "кадр состояния не должен включать кнопку обратно: debounce ведёт onHeartClicked, " +
                "иначе двойной тап шлёт два запроса",
            update.contains("h.heart.isEnabled = state.enabled"),
        )
    }

    // --- 5. метаданные при смене трека (баг веб-версии) ---

    @Test
    fun `metadata follows every player state frame`() {
        val renderState = funBody(musicSources, "internal fun MusicActivity.renderPlayerState(")
        assertTrue(
            "раскрытый плеер обязан перерисовываться на КАЖДЫЙ кадр состояния плеера — " +
                "иначе автопереход оставит название, артиста и обложку предыдущего трека",
            renderState.contains("updateFullPlayer()"),
        )
        val update = funBody(musicSources, "internal fun MusicActivity.updateFullPlayer()")
        assertTrue(
            "заголовок выводится из текущего трека, а не из снимка, снятого при открытии",
            update.contains("MusicFullPlayerPolicy.title(track)"),
        )
        assertTrue(
            "артист обновляется тем же кадром",
            update.contains("MusicFullPlayerPolicy.artist(track)"),
        )
        assertTrue(
            "обложка перезагружается при смене URL",
            update.contains("MusicFullPlayerPolicy.shouldReloadArtwork("),
        )
    }

    @Test
    fun `progress keeps ticking between state frames`() {
        assertTrue(
            "позиция в раскрытом плеере обязана двигаться на тикере экрана, а не только на событиях плеера",
            musicSources.contains("updateFullPlayerProgress(MarsMusicPlayer.currentPosition, MarsMusicPlayer.duration)"),
        )
        val progress = funBody(musicSources, "internal fun MusicActivity.updateFullPlayerProgress(")
        assertTrue(
            "тумб не двигается, пока палец владеет перемоткой — то же правило, что в полосе",
            progress.contains("SeekDragPolicy.tickerMayUpdate(h.isDragging)"),
        )
    }

    // --- 6/7. дизайн-дисциплина и доступность ---

    @Test
    fun `full player has no color literals and no glyph text`() {
        val section = source("src/main/java/ru/mars/mobile/MusicFullPlayerSection.kt")
        assertFalse(
            "цвет берётся токеном MarsTheme: литерал 0xff в экране запрещён",
            section.contains("0xff") || section.contains("0xFF"),
        )
        listOf("×", "●", "▲", "♫", "▎", "─", "✓", "★").forEach { glyph ->
            assertFalse("типографский символ '$glyph' в UI запрещён — только vector-drawable", section.contains(glyph))
        }
        assertTrue(
            "иконки — только vector-drawable из res/drawable",
            section.contains("R.drawable.ic_chevron_down"),
        )
    }

    @Test
    fun `every new button is described for talkback`() {
        val section = source("src/main/java/ru/mars/mobile/MusicFullPlayerSection.kt")
        listOf(
            "MusicFullPlayerPolicy.ACTION_CLOSE",
            "MusicFullPlayerPolicy.ACTION_LYRICS",
            "MusicA11yPolicy.heartAction(false)",
            "MusicA11yPolicy.playPauseAction(isPlaying = false)",
            "MusicA11yPolicy.shuffleAction(shuffleOn = false)",
            "MusicA11yPolicy.repeatAction(MusicQueueManager.RepeatMode.OFF)",
        ).forEach { description ->
            assertTrue("кнопка без contentDescription: $description", section.contains(description))
        }
        assertTrue(
            "prev/next обязаны быть озвучены",
            section.contains("\"Предыдущий трек\"") && section.contains("\"Следующий трек\""),
        )
    }

    @Test
    fun `transport buttons keep the compact bar sizes`() {
        val section = source("src/main/java/ru/mars/mobile/MusicFullPlayerSection.kt")
        assertFalse(
            "кнопки не увеличиваются «для тач-френдли»: размеры — как у полосы плеера",
            section.contains("dp(48)") || section.contains("dp(56)") || section.contains("dp(64)"),
        )
        assertTrue("главная кнопка остаётся 52dp, как в полосе", section.contains("dp(52), dp(52)"))
    }
}
