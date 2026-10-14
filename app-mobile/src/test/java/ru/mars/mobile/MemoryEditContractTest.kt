package ru.mars.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт проводки правки памяти в [MemoryActivity] (паттерн source-scan как в
 * [MemoryShellContractTest]: instrumented-слоя нет, поэтому фиксация по production-
 * исходнику). Проверяет, что экран:
 * - даёт аккуратную круглую иконку-кнопку «Править» рядом с копированием (без
 *   визуально крупных кнопок — спецификация минимализма);
 * - решает «можно ли править» ЧИСТОЙ политикой [MemoryEditPolicy], а не inline-логикой;
 * - сохраняет строго через [ru.mars.network.repository.MemoryRepository.writeSection]
 *   (`POST /api/memory/write`) и после успеха перезагружает секции с сервера;
 * - необратимых операций экран не добавляет (удаление секций не предусмотрено
 *   серверным контрактом — запись только полная).
 */
class MemoryEditContractTest {

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

    private val memory by lazy { source("src/main/java/ru/mars/mobile/MemoryActivity.kt") }

    @Test
    fun `edit affordance is a subtle circle icon next to copy`() {
        assertTrue("Кнопка правки — circleIconButton с ic_edit", memory.contains("R.drawable.ic_edit"))
        assertTrue("editBtn — поле экрана", memory.contains("editBtn"))
        assertFalse(
            "Крупные FAB/тулбар-кнопки запрещены спецификацией минимализма",
            memory.contains("FloatingActionButton")
        )
    }

    @Test
    fun `edit decisions come from the pure policy`() {
        assertTrue(
            "Доступность правки гасится политикой canEdit",
            memory.contains("MemoryEditPolicy.canEdit(currentTab, state)")
        )
        assertTrue(
            "Имя записываемой секции даёт политика (PROJECT → null, экран не пишет AGENTS.md)",
            memory.contains("MemoryEditPolicy.writableSection(currentTab)")
        )
        assertTrue(
            "Черновик проверяется политикой canSave до отправки",
            memory.contains("MemoryEditPolicy.canSave(draft, original)")
        )
        assertTrue(
            "Причина блокировки сохранения — saveBlockReason (пусто vs без изменений)",
            memory.contains("MemoryEditPolicy.saveBlockReason(draft, original)")
        )
    }

    @Test
    fun `save goes through the repository and reloads the server truth`() {
        assertTrue(
            "Запись — только MemoryRepository.writeSection (POST /api/memory/write)",
            memory.contains("repository.writeSection(MarsApi.BASE_URL, credential, section, draft)")
        )
        assertTrue(
            "После Saved экран перезагружает память с сервера",
            memory.contains("is MemoryWriteResult.Saved ->")
        )
        assertTrue(
            "Ошибка сервера показывается inline-стрипом диалога, а не теряется",
            memory.contains("is MemoryWriteResult.Failed ->")
        )
        assertFalse(
            "Прямых записей файлов памяти в клиенте нет и быть не должно",
            memory.contains("openFileOutput") || memory.contains("FileOutputStream")
        )
    }
}
