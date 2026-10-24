package ru.mars.tv.profiles

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-контракт TV-экрана «Профили» (паттерн `ProfilesScreenContractTest` в
 * app-mobile): инструментального слоя в проекте нет, поэтому обязательства
 * экрана закреплены чтением реальных production-исходников.
 *
 * Проверяется:
 *  - единственный вход — плитка дашборда, экран объявлен в манифесте;
 *  - экран ТОЛЬКО ЧТЕНИЕ: ни экран, ни контроллер, ни репозиторий не обращаются
 *    к мутациям профиля (переключение меняет `HERMES_HOME` живого процесса WebUI —
 *    это состояние владельца, а не TV-пульта);
 *  - D-pad: карточка фокусируема и кликабельна, фокус восстанавливается по индексу,
 *    модальных перекрытий (ловушек фокуса) экран не создаёт;
 *  - палитра — только токены темы (`@color/mars_*`, `bg_focus_card`), без хардкода;
 *  - переход в чат профиля: extra кладёт экран профилей, читает чат.
 */
class TvProfilesScreenContractTest {

    /** Файл исходников от корня репозитория (cwd unit-теста — модуль app-tv). */
    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (base in listOf(dir, File(dir, "app-tv"))) {
                val candidate = File(base, relative)
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("Не найден исходник: $relative")
    }

    private val activity by lazy { source("src/main/java/ru/mars/tv/profiles/TvProfilesActivity.kt") }
    private val policy by lazy { source("src/main/java/ru/mars/tv/profiles/TvProfilesPolicy.kt") }
    private val controller by lazy { source("src/main/java/ru/mars/tv/profiles/TvProfilesController.kt") }
    private val routing by lazy { source("src/main/java/ru/mars/tv/profiles/TvProfilesRouting.kt") }
    private val manifest by lazy { source("src/main/AndroidManifest.xml") }
    private val dashboardActivity by lazy { source("src/main/java/ru/mars/tv/DashboardActivity.kt") }
    private val dashboardLayout by lazy { source("src/main/res/layout/activity_dashboard.xml") }
    private val screenLayout by lazy { source("src/main/res/layout/activity_tv_profiles.xml") }
    private val cardLayout by lazy { source("src/main/res/layout/item_tv_profile.xml") }
    /** Каталог production-исходников экрана (cwd unit-теста — модуль app-tv). */
    private val chatSourceDir: File by lazy {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (base in listOf(dir, File(dir, "app-tv"))) {
                val candidate = File(base, "src/main/java/ru/mars/tv/TvChatActivity.kt")
                if (candidate.isFile) return@lazy candidate.absoluteFile.parentFile!!
            }
            dir = dir.parentFile
        }
        throw AssertionError("Не найден каталог исходников TvChatActivity.kt")
    }

    /**
     * Чат целиком: хост плюс его секции (`TvChat*Section.kt`). После декомпозиции
     * экрана чтение extra может жить в любой секции — контракт проверяет экран, а не
     * конкретный файл (тот же приём, что в контрактах MusicActivity).
     */
    private val chatActivity by lazy {
        val sections = chatSourceDir
            .listFiles { f -> f.isFile && f.name.startsWith("TvChat") && f.name.endsWith("Section.kt") }
            ?.sortedBy { it.name }
            ?.joinToString("\n") { it.readText() }
            .orEmpty()
        source("src/main/java/ru/mars/tv/TvChatActivity.kt") + "\n" + sections
    }

    private val chatFlow by lazy { source("src/main/java/ru/mars/tv/TvChatSessionFlow.kt") }
    private val repository by lazy {
        source("core-network/src/main/java/ru/mars/network/repository/ProfilesRepository.kt")
    }

    /** Экранные исходники профилей: всё, что могло бы позвать мутацию. */
    private val screenSources by lazy { listOf(activity, policy, controller, routing) }

    /**
     * Код без комментариев: KDoc экрана ОБЪЯСНЯЕТ, почему мутаций профиля нет
     * («серверный switch меняет HERMES_HOME»), и такая строка — не вызов.
     */
    private fun code(text: String): String = text
        .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\n]*"), "")

    @Test
    fun `screen is declared in the manifest`() {
        assertTrue(
            "экран профилей обязан быть объявлен в манифесте",
            manifest.contains(".profiles.TvProfilesActivity"),
        )
    }

    @Test
    fun `entry point is the dashboard tile only`() {
        assertTrue(
            "плитка «Профили» обязана открывать экран",
            dashboardActivity.contains("TvProfilesActivity::class.java"),
        )
        assertTrue("плитка обязана быть в сетке дашборда", dashboardLayout.contains("@+id/profilesButton"))
        assertTrue(
            "плитка обязана получать подъём под фокусом",
            dashboardActivity.contains("findViewById(R.id.profilesButton)"),
        )
    }

    @Test
    fun `screen never calls a profile mutation`() {
        val mutations = listOf(
            ".post(", ".put(", ".delete(", ".patch(",
            "switchProfile", "renameProfile", "deleteProfile", "createProfile", "updateProfile",
            "api/profiles/switch", "api/profiles/add", "api/profiles/delete", "api/profiles/rename",
        )
        screenSources.forEach { text ->
            mutations.forEach { needle ->
                assertFalse("экран профилей read-only, найдено: $needle", code(text).contains(needle))
            }
        }
    }

    @Test
    fun `repository reads the catalog over GET and exposes no mutation`() {
        val text = code(repository)
        assertTrue("репозиторий обязан читать каталог", text.contains("api/profiles"))
        assertFalse("репозиторий не должен слать POST", text.contains(".post("))
        assertFalse("репозиторий не должен слать PUT", text.contains(".put("))
        assertFalse("репозиторий не должен слать DELETE", text.contains(".delete("))
        assertFalse("переключения профиля в клиенте нет", text.contains("switch", ignoreCase = true))
        assertTrue("репозиторий читает GET'ом", text.contains(".get()"))
    }

    @Test
    fun `controller owns exactly one read call and no mutation path`() {
        val text = code(controller)
        assertTrue(
            "контроллер обязан читать каталог через репозиторий",
            text.contains("repository.getProfiles("),
        )
        assertFalse("контроллер не переключает профиль", text.contains("switch", ignoreCase = true))
        assertFalse("контроллер не переименовывает профиль", text.contains("rename", ignoreCase = true))
        assertFalse("контроллер не удаляет профиль", text.contains("deleteProfile"))
    }

    @Test
    fun `policy carries no mutation decisions`() {
        val text = code(policy)
        listOf("switch", "rename", "delete", "create").forEach { verb ->
            assertFalse(
                "политика только читает: найдено $verb",
                text.contains(verb, ignoreCase = true),
            )
        }
    }

    @Test
    fun `cards are D-pad focusable and clickable with a theme focus selector`() {
        assertTrue("карточка фокусируема", cardLayout.contains("android:focusable=\"true\""))
        assertTrue("карточка кликабельна", cardLayout.contains("android:clickable=\"true\""))
        assertTrue(
            "карточка обязана использовать фокусный селектор темы",
            cardLayout.contains("android:background=\"@drawable/bg_focus_card\""),
        )
        assertTrue("экран навешивает подъём под фокус", activity.contains("TvFocusLift.attach(card"))
    }

    @Test
    fun `focus survives a re-render and is restored by index`() {
        assertTrue(
            "фокус восстанавливается по индексу",
            activity.contains("TvProfilesPolicy.restoreSelection("),
        )
        assertTrue("индекс переживает пересоздание экрана", activity.contains("STATE_SELECTED"))
        assertTrue("позиция карточки запоминается", activity.contains("selectedIndex = profilesContainer.indexOfChild(card)"))
    }

    @Test
    fun `screen adds no modal focus trap`() {
        listOf("AlertDialog", "DialogFragment", "PopupWindow", "Dialog(").forEach { trap ->
            assertFalse("модальных ловушек фокуса быть не должно: $trap", activity.contains(trap))
        }
    }

    @Test
    fun `screen uses theme tokens only, no hardcoded colors`() {
        listOf(activity, policy, controller, routing).forEach { text ->
            assertFalse("хардкод-цвет 0xAARRGGBB в Kotlin", Regex("0x[0-9a-fA-F]{6,8}").containsMatchIn(text))
            assertFalse("Color.parseColor в экране", text.contains("parseColor("))
            assertFalse("именованный цвет Android", Regex("Color\\.(WHITE|BLACK)").containsMatchIn(text))
        }
        assertTrue("экран берёт фон темы", screenLayout.contains("@drawable/bg_tv_root"))
        assertTrue("карточка берёт фокусный фон темы", cardLayout.contains("@drawable/bg_focus_card"))
        assertTrue("текст карточки — токен палитры", cardLayout.contains("@color/mars_text_strong"))
    }

    @Test
    fun `chat of a profile is opened through the documented extra`() {
        assertTrue(
            "экран кладёт имя профиля в extra чата",
            activity.contains("TvProfilesRouting.EXTRA_PROFILE"),
        )
        assertTrue("чат читает тот же extra", chatActivity.contains("TvProfilesRouting.EXTRA_PROFILE"))
        assertTrue(
            "чат передаёт профиль в создание сессии",
            chatFlow.contains("ARG_PROFILE"),
        )
        assertTrue(
            "имя профиля нормализуется перед отправкой",
            routing.contains("fun profileExtra("),
        )
    }

    @Test
    fun `empty catalog has an honest line of its own`() {
        assertTrue("пустой каталог объясняется текстом", policy.contains("EMPTY_LIST"))
        assertTrue("экран рисует пустое состояние", screenLayout.contains("@+id/emptyText"))
        assertTrue("пустое состояние не выдаётся за ошибку", activity.contains("TvProfilesPolicy.emptyLine("))
    }
}
