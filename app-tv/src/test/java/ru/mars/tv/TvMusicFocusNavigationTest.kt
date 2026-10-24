package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D-pad навигация экрана Музыки: фокус не застревает в поле поиска, BACK сначала
 * закрывает экранную клавиатуру. Robolectric в модуле нет (build.gradle.kts — только
 * junit/mockwebserver/org.json), поэтому поведение проверяется в трёх слоях:
 *  1. чистая политика [TvMusicFocusPolicy] (цепочки, DOWN-цель, BACK-решение);
 *  2. парсинг activity_tv_music.xml — взаимные android:nextFocus* всей верхней панели;
 *  3. присутствие код-механизма в исходниках экрана — хост [TvMusicActivity] плюс его
 *     секции `TvMusic*Section.kt` (динамические id строк треков, rewire* после рендера,
 *     OnBackPressedCallback + скрытие IME).
 * Фактический FocusSearch на устройстве здесь не выполняется (нет инструментации).
 */
class TvMusicFocusNavigationTest {

    private val moduleDir = File(System.getProperty("user.dir"))
    private val layout = File(moduleDir, "src/main/res/layout/activity_tv_music.xml").readText()
    /**
     * Исходники экрана: хост [TvMusicActivity] ПЛЮС его секции `TvMusic*Section.kt`.
     *
     * Читать один файл по имени нельзя: после декомпозиции хоста код-механизм D-pad живёт
     * в секциях, и тест краснел бы от самого факта выноса, а не от поломки фокуса. Список
     * секций берётся с диска (glob) — новый секционный файл подхватывается сам.
     */
    private val activitySource: String by lazy {
        val dir = File(moduleDir, "src/main/java/ru/mars/tv")
        val sections = dir
            .listFiles { f -> f.isFile && f.name.startsWith("TvMusic") && f.name.endsWith("Section.kt") }
            ?.sortedBy { it.name }
            .orEmpty()
        (listOf(File(dir, "TvMusicActivity.kt")) + sections).joinToString("\n") { it.readText() }
    }

    private fun block(id: String): String {
        val re = Regex(
            """<(?:Button|EditText|ImageView)\b[^>]*?android:id="@\+id/$id"[^>]*?/>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        return re.find(layout)?.value ?: error("view @$id not found in activity_tv_music.xml")
    }

    private fun attr(block: String, name: String): String? =
        Regex("""android:$name="([^"]*)"""").find(block)?.groupValues?.get(1)

    private val topPanelIds = listOf(
        "backButton", "repeatButton", "lyricsButton", "playPauseButton", "stopButton",
        "searchInput", "searchButton", "chartsButton", "retryButton",
    )

    // --- 2. XML: взаимные nextFocus* цепочки -------------------------------------------

    @Test
    fun `every top panel element carries all four nextFocus attributes`() {
        topPanelIds.forEach { id ->
            val block = block(id)
            listOf("nextFocusLeft", "nextFocusRight", "nextFocusUp", "nextFocusDown").forEach { name ->
                val value = attr(block, name)
                assertTrue("@$id missing android:$name", value != null)
                assertTrue(
                    "@$id android:$name must reference a view id, was $value",
                    value!!.startsWith("@+id/") || value.startsWith("@id/"),
                )
            }
        }
    }

    @Test
    fun `DOWN from backButton enters searchInput and DOWN from searchInput targets the track list`() {
        assertEquals("@+id/searchInput", attr(block("backButton"), "nextFocusDown"))
        // XML-fallback на контейнер списка; на конкретный первый трек цель наводит код
        // (rewireSearchDownFocus) после рендера динамических строк.
        assertEquals("@+id/trackList", attr(block("searchInput"), "nextFocusDown"))
    }

    @Test
    fun `searchInput never pops the IME on plain focus`() {
        // D-pad проходит через поле вниз без автоматической экранной клавиатуры.
        // XML-атрибут showSoftInputOnFocus скрыт в платформе — выключаем его кодом.
        assertTrue(
            "soft input on focus must be disabled in code",
            activitySource.contains("searchInput.setShowSoftInputOnFocus(false)"),
        )
    }

    @Test
    fun `trackList yields focus to its first focusable track row`() {
        val re = Regex("""<LinearLayout\b[^>]*android:id="@\+id/trackList"[^>]*>""", RegexOption.DOT_MATCHES_ALL)
        val trackList = re.find(layout)?.value ?: error("trackList container not found")
        assertEquals("afterDescendants", attr(trackList, "descendantFocusability"))
    }

    // --- 1. Чистая политика ------------------------------------------------------------

    @Test
    fun `horizontalChain links visible neighbours and self-loops both ends`() {
        val ids = listOf(1, 2, 3)
        val chain = TvMusicFocusPolicy.horizontalChain(ids)
        // Явные пары: крайние самопетляют, середина — на соседей.
        assertEquals(Pair(1, 2), chain[1])
        assertEquals(Pair(1, 3), chain[2])
        assertEquals(Pair(2, 3), chain[3])
    }

    @Test
    fun `horizontalChain survives a single visible button and an empty header`() {
        assertEquals(Pair(7, 7), TvMusicFocusPolicy.horizontalChain(listOf(7))[7])
        assertTrue(TvMusicFocusPolicy.horizontalChain(emptyList()).isEmpty())
    }

    @Test
    fun `DOWN target prefers first track row, then visible retry, then nothing`() {
        assertEquals(
            42,
            TvMusicFocusPolicy.downTargetFromSearchRow(firstTrackRowId = 42, retryButtonId = 9, retryVisible = true),
        )
        assertEquals(
            9,
            TvMusicFocusPolicy.downTargetFromSearchRow(firstTrackRowId = null, retryButtonId = 9, retryVisible = true),
        )
        assertEquals(
            TvMusicFocusPolicy.NO_FOCUS,
            TvMusicFocusPolicy.downTargetFromSearchRow(firstTrackRowId = null, retryButtonId = 9, retryVisible = false),
        )
        assertEquals(-1, TvMusicFocusPolicy.NO_FOCUS) // == View.NO_ID
    }

    @Test
    fun `BACK closes IME first and the screen only without IME`() {
        assertEquals(
            TvMusicFocusPolicy.BackAction.HIDE_IME,
            TvMusicFocusPolicy.backAction(imeVisible = true),
        )
        assertEquals(
            TvMusicFocusPolicy.BackAction.CLOSE_SCREEN,
            TvMusicFocusPolicy.backAction(imeVisible = false),
        )
    }

    // --- 3. Код-механизм в хосте и секциях (не только XML) ----------------------------

    @Test
    fun `dynamic track rows get real ids and the first row is wired into the focus chain`() {
        assertTrue("rows must get generated ids", activitySource.contains("id = View.generateViewId()"))
        assertTrue(
            "DOWN from the search row must be re-aimed in code after render",
            activitySource.contains("trackList.getChildAt(0)") &&
                activitySource.contains("searchInput.nextFocusDownId = target"),
        )
        assertTrue(
            "first track row must lead UP back into searchInput",
            activitySource.contains("firstRow?.nextFocusUpId = R.id.searchInput"),
        )
        assertTrue(
            "render() must rewire the DOWN chain after every state draw",
            activitySource.contains("rewireSearchDownFocus()"),
        )
        assertTrue(
            "header chain must be rebuilt from visible buttons only",
            activitySource.contains("rewireHeaderFocusChain()") &&
                activitySource.contains("TvMusicFocusPolicy.horizontalChain(visible.map { it.id })"),
        )
    }

    @Test
    fun `BACK handling hides the IME and keeps focus on searchInput before closing the screen`() {
        assertTrue(
            "BACK must be routed through OnBackPressedCallback",
            activitySource.contains("OnBackPressedCallback") &&
                activitySource.contains("TvMusicFocusPolicy.backAction(imeVisible)"),
        )
        assertTrue(
            "IME dismissal keeps focus on searchInput",
            activitySource.contains("hideIme()") && activitySource.contains("searchInput.requestFocus()"),
        )
        assertTrue(
            "IME visibility is measured from the visible display frame",
            activitySource.contains("getWindowVisibleDisplayFrame"),
        )
        assertTrue(
            "IME opens only on explicit activation, not on focus",
            activitySource.contains("searchInput.setOnClickListener { showIme() }"),
        )
    }
}
