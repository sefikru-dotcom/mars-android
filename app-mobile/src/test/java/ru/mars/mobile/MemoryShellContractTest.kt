package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт перевода экрана «Память Марса» на общий shell разделов ([SectionScaffold])
 * и общие состояния ([SectionStateViews]): shell используется и НЕ дублируется
 * экраном, а вкладки/markdown/copy/загрузка сохранены при миграции.
 *
 * Instrumented-слоя для телефона в проекте нет, поэтому проводка закреплена как
 * контракт по РЕАЛЬНОМУ production-исходнику (паттерн [ProjectsShellContractTest] /
 * [ChatClickPathContractTest]): любое удаление shell-вызова, возврат самодельной
 * шапки/insets/спиннера, локальный дубль состояний или потеря markdown/copy-логики
 * ломает тест. Поведенческая часть (что именно решает [MemoryTabPolicy] для
 * поверхности экрана) проверяется на чистой политике в последнем тесте.
 */
class MemoryShellContractTest {

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

    private val memory by lazy { source("src/main/java/ru/mars/mobile/MemoryActivity.kt") }

    // --- Общий shell ---------------------------------------------------------------------

    @Test
    fun `memory screen is built on the shared shell`() {
        assertTrue(
            "Экран обязан создаваться через SectionScaffold с заголовком «Память Марса» и refresh-колбэком load",
            memory.contains("SectionScaffold.create(this, \"Память Марса\", ::load)")
        )
        assertTrue("Контент обязан добавляться в scaffold.content", memory.contains("scaffold.content.addView"))
        assertTrue("Корень экрана — scaffold.root", memory.contains("return scaffold.root"))
    }

    @Test
    fun `memory screen no longer hand-rolls header, insets or spinner`() {
        assertFalse(
            "Своя обработка insets запрещена — её делает shell",
            memory.contains("ViewCompat.setOnApplyWindowInsetsListener")
        )
        assertFalse(
            "Свои кнопки «Назад»/«Обновить» запрещены — их даёт shell",
            memory.contains("R.drawable.ic_arrow_back") || memory.contains("R.drawable.ic_refresh")
        )
        assertFalse(
            "Свой ProgressBar запрещён — спиннер даёт shell",
            memory.contains("ProgressBar(this)")
        )
        assertTrue(
            "Спиннер shell обязан использоваться экраном",
            memory.contains("scaffold.progress")
        )
    }

    @Test
    fun `no local container shadows the scaffold content`() {
        // Главный дефект механической миграции: локальный `val content = ...` затеняет
        // scaffold.content (или поле MemoryContent) и контейнер вкладывает сам себя.
        assertFalse(
            "Локальное объявление `val content` запрещено — есть поле MemoryContent и scaffold.content",
            memory.contains("val content =")
        )
        assertFalse(
            "Локальный `val root = LinearLayout` запрещён — корень даёт shell",
            memory.contains("val root = LinearLayout")
        )
    }

    // --- Общие состояния -------------------------------------------------------------------

    @Test
    fun `shared state views drive loading failure-with-retry and empty`() {
        assertTrue(
            "Загрузка секции — общая state-вью с именем файла",
            memory.contains("SectionStateViews.loading(this, \"Загрузка \${currentTab.fileName}…\")")
        )
        assertTrue(
            "Сбой (сеть/нет входа) — общая error-вью с retry на load",
            memory.contains("SectionStateViews.error(this, state.message, ::load)")
        )
        assertTrue(
            "Пустая секция — общая empty-вью",
            memory.contains("SectionStateViews.empty(this, state.message)")
        )
        assertFalse(
            "Локальный statusText-дубль состояний запрещён",
            memory.contains("private fun statusText")
        )
    }

    @Test
    fun `shell spinner follows the loading state resolved by the policy`() {
        val renderBody = memory.substringAfter("private fun renderBody").substringBefore("private fun load")
        assertTrue(
            "renderBody обязан решать состояние чистой политикой MemoryTabPolicy.resolve",
            renderBody.contains("MemoryTabPolicy.resolve(currentTab, content, loading, failure)")
        )
        assertTrue(
            "Спиннер shell раскрывается только на Loading",
            renderBody.contains("scaffold.progress.visibility = if (state is MemorySectionState.Loading) View.VISIBLE else View.GONE")
        )
    }

    // --- Вкладки, markdown, copy ------------------------------------------------------------

    @Test
    fun `tabs markdown and copy behaviour are preserved`() {
        assertTrue("Чипсы вкладок строятся по MemoryTab.entries", memory.contains("MemoryTab.entries"))
        assertTrue("Выбор вкладки перерисовывает чипсы и тело", memory.contains("currentTab = tab"))
        assertTrue("Markdown-рендер сохранён", memory.contains("MarkdownFormatter.parseBlocks(raw)"))
        assertTrue("Рендер тела — renderMarkdown(state.text, body)", memory.contains("renderMarkdown(state.text, body)"))
        assertTrue("copyBtn жив и связан с copyCurrentSection", memory.contains("setOnClickListener { copyCurrentSection() }"))
        assertTrue(
            "Копирование кладёт текст секции с меткой имени файла",
            memory.contains("ClipData.newPlainText(currentTab.fileName, text)")
        )
        assertTrue(
            "Доступность copy гасится политикой canCopy",
            memory.contains("MemoryTabPolicy.canCopy(currentTab, content)")
        )
    }

    @Test
    fun `load keeps credential check and failure messages from the policy`() {
        val load = memory.substringAfter("private fun load").substringBefore("private fun copyCurrentSection")
        assertTrue("Guard от параллельных загрузок сохранён", load.contains("if (loading || isFinishing) return"))
        assertTrue("Без креденшила — сообщение SIGNED_OUT_FAILURE", load.contains("MemoryTabPolicy.SIGNED_OUT_FAILURE"))
        assertTrue("Сбой сети — сообщение DEFAULT_FAILURE", load.contains("MemoryTabPolicy.DEFAULT_FAILURE"))
        assertTrue("Загрузка идёт через MemoryRepository.getMemory", load.contains("repository.getMemory(MarsApi.BASE_URL, credential)"))
    }

    // --- Поведенческая часть: что именно решает политика для экрана -------------------------

    @Test
    fun `policy decisions consumed by the screen keep their semantics`() {
        val loaded = MemorySectionState.Body("# Заголовок")
        assertEquals(
            "Загрузка важнее всего — даже при готовом контенте",
            MemorySectionState.Loading,
            MemoryTabPolicy.resolve(MemoryTab.MEMORY, contentOf(memory = "# Заголовок"), loading = true)
        )
        assertEquals(
            "Нет ответа — Failure с сообщением политики",
            MemorySectionState.Failure(MemoryTabPolicy.DEFAULT_FAILURE),
            MemoryTabPolicy.resolve(MemoryTab.MEMORY, content = null, loading = false)
        )
        assertEquals(
            "Нет входа — Failure с сообщением SIGNED_OUT_FAILURE (его показывает error-вью с retry)",
            MemorySectionState.Failure(MemoryTabPolicy.SIGNED_OUT_FAILURE),
            MemoryTabPolicy.resolve(MemoryTab.USER, content = null, loading = false, failure = MemoryTabPolicy.SIGNED_OUT_FAILURE)
        )
        assertEquals(
            "Пустая секция — Empty с именем файла",
            MemorySectionState.Empty("Секция SOUL.md пуста"),
            MemoryTabPolicy.resolve(MemoryTab.SOUL, contentOf(), loading = false)
        )
        assertEquals(
            "Непустая секция — Body с обрезанным текстом",
            loaded,
            MemoryTabPolicy.resolve(MemoryTab.MEMORY, contentOf(memory = "  # Заголовок  "), loading = false)
        )
        assertFalse("Пустую секцию копировать нельзя", MemoryTabPolicy.canCopy(MemoryTab.MEMORY, contentOf()))
        assertTrue("Непустую секцию копировать можно", MemoryTabPolicy.canCopy(MemoryTab.MEMORY, contentOf(memory = "текст")))
        assertEquals(
            "Вкладка «Контекст» — это AGENTS.md",
            "AGENTS.md",
            MemoryTabPolicy.resolve(MemoryTab.PROJECT, contentOf(), loading = false)
                .let { (it as MemorySectionState.Empty).message }
                .removePrefix("Секция ").removeSuffix(" пуста")
        )
    }

    private fun contentOf(
        memory: String = "",
        user: String = "",
        soul: String = "",
        projectContext: String = "",
    ) = ru.mars.network.model.MemoryContent(
        memory = memory,
        user = user,
        soul = soul,
        projectContext = projectContext,
    )
}
