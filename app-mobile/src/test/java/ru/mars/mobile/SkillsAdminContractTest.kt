package ru.mars.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт проводки админки скиллов в [SkillsActivity] (source-scan, паттерн
 * [MemoryShellContractTest]). Проверяет, что экран:
 * - показывает `disabled`-состояние скилла бейджем (данные `GET /api/skills`, :251);
 * - toggle/save/delete идут строго через [ru.mars.network.repository.SkillsRepository]
 *   (`POST /api/skills/toggle|save|delete`), а не локальной логикой;
 * - УДАЛЕНИЕ (необратимый rmtree на сервере, route_skills.py `_handle_skill_delete`)
 *   требует явного админ-подтверждения отдельным диалогом с destructive-кнопкой;
 * - после успешной мутации каталог перезагружается с сервера (источник истины — сервер).
 */
class SkillsAdminContractTest {

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

    private val skills by lazy { source("src/main/java/ru/mars/mobile/SkillsActivity.kt") }

    @Test
    fun `disabled state is visible on the card`() {
        assertTrue("Карточка реагирует на skill.disabled", skills.contains("skill.disabled"))
        assertTrue("Бейдж — короткий, из политики", skills.contains("SkillsAdminPolicy.DISABLED_BADGE"))
    }

    @Test
    fun `mutations go through the repository endpoints`() {
        assertTrue(
            "toggle — SkillsRepository.toggleSkill (POST /api/skills/toggle)",
            skills.contains("repository.toggleSkill(MarsApi.BASE_URL, credential, skill.name, enabled = !skill.disabled)")
        )
        assertTrue(
            "save — SkillsRepository.saveSkill (POST /api/skills/save)",
            skills.contains("repository.saveSkill(MarsApi.BASE_URL, credential, name, draft, category)")
        )
        assertTrue(
            "delete — SkillsRepository.deleteSkill (POST /api/skills/delete)",
            skills.contains("repository.deleteSkill(MarsApi.BASE_URL, credential, skill.name)")
        )
        assertFalse(
            "Локального стирания/записи скиллов у клиента нет",
            skills.contains("deleteRecursively") || skills.contains("FileOutputStream")
        )
    }

    @Test
    fun `delete demands an explicit admin confirmation`() {
        assertTrue("Отдельный шаг подтверждения удаления", skills.contains("confirmDeleteSkill(skill)"))
        assertTrue(
            "Текст подтверждения — из политики и предупреждает о необратимости",
            skills.contains("SkillsAdminPolicy.deleteConfirmMessage(skill.name)")
        )
        assertTrue(
            "Необратимое действие — destructive-кнопка denyButton, не primary",
            skills.contains("MarsDialog.denyButton")
        )
    }

    @Test
    fun `catalog reloads after a successful mutation`() {
        assertTrue("Успех мутации перечитывает каталог с сервера", skills.contains("is SkillMutationResult.Ok ->"))
        assertTrue("Ошибка мутации показывается пользователю", skills.contains("is SkillMutationResult.Failed ->"))
    }
}
