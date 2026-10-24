package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Форма экрана «Музыка» после декомпозиции: [TvMusicActivity] — тонкий хост, а тела живут
 * в секциях `TvMusic*Section.kt`.
 *
 * Раскладка нужна не «для красоты»: пока экран был одним файлом на ~1400 строк, любая
 * правка строки трека, диалога альбома или текста песни шла в тот же файл и ревью не видело
 * границ. Тест держит инварианты, без которых раскладка рассыпается обратно:
 * - хост остаётся бюджетным (<= 600 строк): состояние и жизненный цикл, не поведение;
 * - секции — только extension-функции на Activity и НИКАКОГО своего состояния, иначе
 *   появится второй владелец экрана и порядок инициализации станет неочевидным;
 * - чистая политика фокуса лежит отдельным файлом, а не рядом с Activity.
 *
 * Список секций читается с диска (glob `TvMusic*Section.kt`), а не переписывается руками —
 * новый секционный файл подхватывается сам, как в контрактах TvChatActivity и MusicActivity.
 */
class TvMusicDecompositionTest {

    /** Каталог production-исходников экрана (тест не зависит от cwd модуля). */
    private val sourceDir: File by lazy {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val flat = File(dir, "src/main/java/ru/mars/tv/TvMusicActivity.kt")
            if (flat.isFile) return@lazy flat.absoluteFile.parentFile!!
            val nested = File(dir, "app-tv/src/main/java/ru/mars/tv/TvMusicActivity.kt")
            if (nested.isFile) return@lazy nested.absoluteFile.parentFile!!
            dir = dir.parentFile
        }
        throw AssertionError("Не найден каталог исходников TvMusicActivity.kt")
    }

    private val hostFile: File get() = File(sourceDir, "TvMusicActivity.kt")

    private val host: String by lazy { hostFile.readText() }

    /** Все секции экрана: `TvMusicChartsSection.kt`, `TvMusicTracksSection.kt`, … */
    private val sections: Map<String, String> by lazy {
        sourceDir.listFiles { f -> f.isFile && f.name.startsWith("TvMusic") && f.name.endsWith("Section.kt") }
            ?.sortedBy { it.name }
            ?.associate { it.name to it.readText() }
            .orEmpty()
    }

    /** Верхнеуровневые объявления секции: имя файла и строка объявления. */
    private fun topLevelDecls(text: String): List<String> =
        text.lines().filter {
            it.matches(Regex("^(internal|private|fun|val|var|class|object|lateinit|override) .*"))
        }

    private fun hostFunctions(): Set<String> =
        Regex("""^    (?:private |internal |override )*(?:suspend )?fun ([A-Za-z0-9_]+)\(""", RegexOption.MULTILINE)
            .findAll(host)
            .map { it.groupValues[1] }
            .toSet()

    private fun sectionFunctions(text: String): List<String> =
        Regex("""^internal (?:suspend )?fun TvMusicActivity\.([A-Za-z0-9_]+)\(""", RegexOption.MULTILINE)
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()

    @Test
    fun hostStaysUnderBudget() {
        val lines = host.lines().size
        assertTrue(
            "хост TvMusicActivity разросся до $lines строк (бюджет 600): поведение должно жить в TvMusic*Section.kt",
            lines <= 600,
        )
    }

    @Test
    fun sectionsExistAndCoverTheScreen() {
        assertTrue("секции экрана не найдены рядом с хостом: ${sections.keys}", sections.size >= 5)
        val names = sections.values.flatMap { sectionFunctions(it) }
        assertTrue(
            "в секциях не нашлось extension-функций TvMusicActivity — раскладка потеряна",
            names.size >= 30,
        )
    }

    @Test
    fun sectionsAreExtensionFunctionsOnly() {
        for ((file, text) in sections) {
            for (decl in topLevelDecls(text)) {
                assertTrue(
                    "$file: верхнеуровневое объявление должно быть extension-функцией на хосте, а не '$decl'",
                    decl.startsWith("internal fun TvMusicActivity."),
                )
            }
        }
    }

    @Test
    fun sectionsHoldNoState() {
        for ((file, text) in sections) {
            for (decl in topLevelDecls(text)) {
                val stateful = decl.startsWith("internal val ") || decl.startsWith("internal var ") ||
                    decl.startsWith("internal lateinit ") || decl.startsWith("val ") || decl.startsWith("var ")
                assertTrue(
                    "$file: состояние экрана обязано жить в хосте, найдено '$decl'",
                    !stateful,
                )
            }
        }
    }

    @Test
    fun everySectionFunctionIsDeclaredOnce() {
        val all = mutableListOf<String>()
        for ((_, text) in sections) all += sectionFunctions(text)
        val duplicates = all.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertEquals("одна и та же функция объявлена в двух секциях: $duplicates", emptyMap<String, Int>(), duplicates)
        val hostOnly = hostFunctions().intersect(all.toSet())
        assertEquals(
            "функция осталась в хосте и одновременно перенесена в секцию: $hostOnly",
            emptySet<String>(),
            hostOnly,
        )
    }

    /**
     * Чистая политика фокуса — отдельный файл: она проверяется своим тестом
     * ([TvMusicFocusPolicyTest]) и не должна снова врастать в Activity.
     */
    @Test
    fun focusPolicyLivesInItsOwnFile() {
        val policy = File(sourceDir, "TvMusicFocusPolicy.kt")
        assertTrue("TvMusicFocusPolicy.kt пропал", policy.isFile)
        assertTrue(
            "объект политики обязан быть в TvMusicFocusPolicy.kt",
            policy.readText().contains("object TvMusicFocusPolicy"),
        )
        assertFalse(
            "объект политики снова объявлен внутри хоста",
            host.contains("object TvMusicFocusPolicy"),
        )
    }
}
