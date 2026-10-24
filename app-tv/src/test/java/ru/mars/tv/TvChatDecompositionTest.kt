package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Форма экрана ТВ-чата после декомпозиции: [TvChatActivity] — тонкий хост, а тела
 * живут в секциях `TvChat*Section.kt`.
 *
 * Раскладка нужна не «для красоты»: пока экран был одним файлом на ~3000 строк, любая
 * правка ленты, хода или параметров шла в тот же файл и ревью не видело границ. Тест
 * держит ДВА инварианта, без которых раскладка рассыпается обратно:
 * - хост остаётся бюджетным (<= 600 строк): состояние и жизненный цикл, не поведение;
 * - секции — только extension-функции на Activity и НИКАКОГО своего состояния, иначе
 *   появится второй владелец экрана и порядок инициализации станет неочевидным.
 *
 * Список секций читается с диска (glob `TvChat*Section.kt`), а не переписывается
 * руками — новый секционный файл подхватывается сам, как в контрактах MusicActivity.
 */
class TvChatDecompositionTest {

    /** Каталог production-исходников экрана (тест не зависит от cwd модуля). */
    private val sourceDir: File by lazy {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val flat = File(dir, "src/main/java/ru/mars/tv/TvChatActivity.kt")
            if (flat.isFile) return@lazy flat.absoluteFile.parentFile!!
            val nested = File(dir, "app-tv/src/main/java/ru/mars/tv/TvChatActivity.kt")
            if (nested.isFile) return@lazy nested.absoluteFile.parentFile!!
            dir = dir.parentFile
        }
        throw AssertionError("Не найден каталог исходников TvChatActivity.kt")
    }

    private val hostFile: File get() = File(sourceDir, "TvChatActivity.kt")

    private val host: String by lazy { hostFile.readText() }

    /** Все секции экрана: `TvChatTurnSection.kt`, `TvChatTranscriptSection.kt`, … */
    private val sections: Map<String, String> by lazy {
        sourceDir.listFiles { f -> f.isFile && f.name.startsWith("TvChat") && f.name.endsWith("Section.kt") }
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
        Regex("""^internal (?:suspend )?fun TvChatActivity\.([A-Za-z0-9_]+)\(""", RegexOption.MULTILINE)
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()

    @Test
    fun hostStaysUnderBudget() {
        val lines = host.lines().size
        assertTrue(
            "хост TvChatActivity разросся до $lines строк (бюджет 600): поведение должно жить в TvChat*Section.kt",
            lines <= 600,
        )
    }

    @Test
    fun sectionsExistAndCoverTheScreen() {
        assertTrue("секции экрана не найдены рядом с хостом", sections.size >= 8)
        val names = sections.values.flatMap { sectionFunctions(it) }
        assertTrue(
            "в секциях не нашлось extension-функций TvChatActivity — раскладка потеряна",
            names.size >= 80,
        )
    }

    @Test
    fun sectionsAreExtensionFunctionsOnly() {
        for ((file, text) in sections) {
            for (decl in topLevelDecls(text)) {
                val ok = decl.startsWith("internal fun TvChatActivity.") ||
                    decl.startsWith("internal suspend fun TvChatActivity.") ||
                    decl.startsWith("internal class ") // чистые данные без состояния (TurnOutcome)
                assertTrue(
                    "$file: верхнеуровневое объявление должно быть extension-функцией на хосте, а не '$decl'",
                    ok,
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
}
