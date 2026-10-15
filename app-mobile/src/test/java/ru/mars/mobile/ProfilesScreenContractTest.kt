package ru.mars.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-контракт экрана «Профили» (паттерн [SettingsSourceContractTest]):
 * инструментального слоя в проекте нет, поэтому обязательства экрана закреплены
 * чтением реальных production-исходников.
 *
 * Проверяется:
 *  - единственный вход — из «Настройки» ([AppDestination.SETTINGS]), без нового
 *    раздела левого бара;
 *  - экран ТОЛЬКО ЧТЕНИЕ: ни экран, ни репозиторий не обращаются к мутациям
 *    профиля (переключение меняет `HERMES_HOME` живого процесса WebUI — это
 *    состояние владельца, а не мобильного клиента);
 *  - палитра [MarsTheme] без хардкод-цветов (`scripts/theme-parity-audit.py`);
 *  - тексты — из `res/values/strings.xml`, а не литералами экрана.
 */
class ProfilesScreenContractTest {

    /** Файл исходников от корня репозитория (cwd unit-теста — модуль app-mobile). */
    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (base in listOf(dir, File(dir, "app-mobile"))) {
                val candidate = File(base, relative)
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("Не найден исходник: $relative")
    }

    /** Исходники модуля app-mobile (main) — для проверок «во всём клиенте нет мутаций профиля». */
    private fun mobileSources(relative: String): List<Pair<String, String>> {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val root = File(dir, "app-mobile/$relative")
            if (root.isDirectory) {
                return root.walkTopDown().filter { it.isFile }.map { it.path to it.readText() }.toList()
            }
            dir = dir.parentFile
        }
        throw AssertionError("Не найден каталог исходников: app-mobile/$relative")
    }

    private val screen by lazy { source("src/main/java/ru/mars/mobile/ProfilesActivity.kt") }
    private val policy by lazy { source("src/main/java/ru/mars/mobile/ProfilesPolicy.kt") }
    private val presenter by lazy { source("src/main/java/ru/mars/mobile/ProfilesPresenter.kt") }
    private val settings by lazy { source("src/main/java/ru/mars/mobile/SettingsActivity.kt") }
    private val destination by lazy { source("src/main/java/ru/mars/mobile/AppDestination.kt") }
    private val manifest by lazy { source("src/main/AndroidManifest.xml") }
    private val strings by lazy { source("src/main/res/values/strings.xml") }
    private val repository by lazy {
        source("core-network/src/main/java/ru/mars/network/repository/ProfilesRepository.kt")
    }

    @Test
    fun `entry point is the existing Settings section only`() {
        assertTrue(
            "вход в экран профилей обязан быть строкой в Настройках",
            settings.contains("ProfilesActivity::class.java"),
        )
        assertTrue(
            "строка входа подписана ресурсом, а не литералом",
            settings.contains("R.string.settings_profiles_open"),
        )
        assertTrue("экран объявлен в манифесте", manifest.contains("android:name=\".ProfilesActivity\""))
        assertFalse(
            "профили не становятся разделом левого бара: порядок разделов — отдельный контракт",
            destination.contains("\"profiles\""),
        )
    }

    @Test
    fun `screen is read-only - the client never calls a profile mutation`() {
        val mutations = listOf("/switch", "ProfileSwitch", "/api/profile/create", "/api/profile/delete")
        for ((name, file) in mobileSources("src/main/java")) {
            for (mutation in mutations) {
                assertFalse("$name обращается к мутации профиля ($mutation)", file.contains(mutation))
            }
        }
        assertFalse(
            "в репозитории профилей допустимы только GET",
            repository.contains(".post(") || repository.contains("RequestBody") || repository.contains("/switch"),
        )
        assertTrue("репозиторий читает именно /api/profiles", repository.contains("api/profiles"))
        assertTrue("строка входящего списка читается с экрана профилей", screen.contains("ProfilesActivity"))
        assertTrue(
            "намеренная read-only природа экрана зафиксирована текстом для пользователя",
            strings.contains("name=\"profiles_readonly_note\""),
        )
    }

    @Test
    fun `palette is taken from MarsTheme - no hardcoded colors`() {
        for ((name, text) in listOf("экран" to screen, "политика" to policy, "презентер" to presenter)) {
            assertFalse("хардкод-цвет ($name)", Regex("""0x[0-9a-fA-F]{6,8}\b""").containsMatchIn(text))
            assertFalse("parseColor запрещён ($name)", text.contains("Color.parseColor"))
        }
        assertTrue(
            "карточки и точки — фабрики палитры",
            screen.contains("MarsTheme.card(") && screen.contains("MarsTheme.statusDot("),
        )
    }

    @Test
    fun `user-facing texts live in resources`() {
        for ((name, text) in listOf("экран" to screen, "политика" to policy, "презентер" to presenter)) {
            assertFalse("$name собирает видимый текст литералом", text.contains("\"Профили\""))
        }
        assertTrue("заголовок экрана — ресурс", screen.contains("R.string.profiles_screen_title"))
        assertTrue("пояснение режима — ресурс", screen.contains("R.string.profiles_notice_"))
        assertTrue("ошибки — ресурсы", screen.contains("R.string.profiles_failure_"))
        assertTrue("подпись активного — ресурс", screen.contains("R.string.profiles_active_"))
        for (key in listOf(
            "profiles_screen_title",
            "profiles_active_line",
            "profiles_active_unknown",
            "profiles_active_badge",
            "profiles_default_badge",
            "profiles_hidden_badge",
            "profiles_no_configuration",
            "profiles_empty",
            "profiles_notice_concept",
            "profiles_notice_single_mode",
            "profiles_readonly_note",
        )) {
            assertTrue("строка $key обязана быть в strings.xml", strings.contains("name=\"$key\""))
        }
        assertTrue("счётчик скиллов — плюрализация ресурсом", strings.contains("name=\"profiles_skills\""))
    }
}
