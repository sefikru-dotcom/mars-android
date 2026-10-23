package ru.mars.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-контракт чартового fallback'а «Обзора» (паттерн [MusicAlbumUiContractTest]:
 * инструментального слоя в проекте нет, поэтому проводка закреплена чтением РЕАЛЬНОГО
 * production-исходника [MusicActivity]).
 *
 * Аудит: `fetchHomeText()` был ВТОРЫМ транспортом для того же `GET /api/home` — сырой
 * HttpURLConnection со своими таймаутами (8/10 с) и своим UA рядом с OkHttp-клиентом
 * репозитория (общие интерсепторы и таймауты). Fallback чарта обязан идти через
 * `MusicRepository.getHome()`, а `{success:false,error}` — оставаться исключением,
 * которое ловит `runCatching`: fallback не должен вырождаться в молчаливый успех с
 * пустым списком (иначе серверный отказ показывается как «Треки не найдены»).
 */
class MusicChartsFallbackContractTest {

    private fun source(relative: String): String {
        // Unit-тесты Gradle работают из каталога модуля app-mobile; подъём вверх до корня
        // репозитория — подстраховка для worktree/CI с другим cwd.
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

    private val activity by lazy { source("src/main/java/ru/mars/mobile/MusicActivity.kt") }

    /** Тело [MusicActivity.loadHome] — единственное место, где решается исход чартов. */
    private val loadHome by lazy {
        activity.substringAfter("private fun loadHome() {")
            .substringBefore("private fun showCachedCharts()")
    }

    @Test
    fun `chart fallback loads home through the repository`() {
        assertTrue(
            "Fallback чарта обязан идти через тот же транспорт, что и чарты: repository.getHome()",
            loadHome.contains("repository.getHome()"),
        )
        assertFalse(
            "Второго транспорта для /api/home в экране быть не должно",
            activity.contains("fetchHomeText") ||
                activity.contains("HttpURLConnection") ||
                activity.contains("openConnection"),
        )
    }

    @Test
    fun `fallback stays a fallback and keeps the rejection semantics`() {
        assertTrue(
            "Fallback вызывается только при падении основного /api/charts",
            loadHome.contains("MusicChartLoader.resolve(runCatching { repository.getCharts() })"),
        )
        assertTrue(
            "getHome() кидает на {success:false} — runCatching обязан это ловить, " +
                "иначе серверный отказ станет «Треки не найдены»",
            loadHome.contains("runCatching { repository.getHome() }"),
        )
        assertTrue(
            "Полки /api/home разворачиваются в плоский список чистой политикой, " +
                "а не ручным разбором JSON в экране",
            loadHome.contains(".map(MusicHomePolicy::playbackQueue)"),
        )
        assertFalse(
            "Ручной разбор payload'а в экране — признак второго транспорта/парсера",
            loadHome.contains("MusicChartLoader.parseHomePayload"),
        )
    }
}
