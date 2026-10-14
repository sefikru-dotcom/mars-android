package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.SkillItem

/**
 * Правила админки скиллов с телефона (контракты hermes-webui api/route_skills.py):
 * - `_handle_skill_save` (:464): имя strip → lowercase → пробелы в дефисы;
 *   `""`, `/`, `..` → 400 "Invalid skill name"; категория с `/` или `..` → 400;
 * - `_handle_skill_delete` (:495): rmtree — необратимо, нужно явное подтверждение;
 * - `_handle_skill_toggle` (:536): enabled=false валиден (require пропускает False).
 */
class SkillsAdminPolicyTest {

    @Test
    fun normalizeNameMirrorsServerTransform() {
        assertEquals("my-skill", SkillsAdminPolicy.normalizeName("  My Skill  "))
        assertEquals("mars-toolkit", SkillsAdminPolicy.normalizeName("MARS-TOOLKIT"))
        assertEquals("a-b-c", SkillsAdminPolicy.normalizeName("A B  C".replace("  ", " ")))
        assertEquals("", SkillsAdminPolicy.normalizeName("   "))
    }

    @Test
    fun nameValidationRejectsServerSide400Cases() {
        assertFalse(SkillsAdminPolicy.isNameValid(""))
        assertFalse(SkillsAdminPolicy.isNameValid("   "))
        assertFalse(SkillsAdminPolicy.isNameValid("a/b"))
        assertFalse(SkillsAdminPolicy.isNameValid(".."))
        assertFalse(SkillsAdminPolicy.isNameValid("a..b"))
        assertFalse(SkillsAdminPolicy.isNameValid("/"))
        assertTrue(SkillsAdminPolicy.isNameValid("My Skill"))
        assertTrue(SkillsAdminPolicy.isNameValid("skill-01"))
    }

    @Test
    fun categoryValidationRejectsPathTraversal() {
        assertTrue(SkillsAdminPolicy.isCategoryValid("devops"))
        assertTrue("Пустая категория законна — скилл ляжет в корень", SkillsAdminPolicy.isCategoryValid(""))
        assertFalse(SkillsAdminPolicy.isCategoryValid("a/b"))
        assertFalse(SkillsAdminPolicy.isCategoryValid(".."))
    }

    @Test
    fun toggleLabelsFollowCurrentState() {
        assertEquals("Выключить", SkillsAdminPolicy.toggleLabel(currentlyEnabled = true))
        assertEquals("Включить", SkillsAdminPolicy.toggleLabel(currentlyEnabled = false))
        assertEquals("Скилл «x» включён", SkillsAdminPolicy.toggledMessage("x", enabled = true))
        assertEquals("Скилл «x» выключен", SkillsAdminPolicy.toggledMessage("x", enabled = false))
    }

    @Test
    fun deleteRequiresExplicitIrreversibilityWarning() {
        val message = SkillsAdminPolicy.deleteConfirmMessage("old-skill")
        assertTrue("Имя скилла в тексте подтверждения", message.contains("old-skill"))
        assertTrue("Необратимость обязана быть названа", message.contains("безвозвратно"))
        assertEquals("Скилл «old-skill» удалён", SkillsAdminPolicy.deletedMessage("old-skill"))
        assertEquals("Скилл «new» сохранён", SkillsAdminPolicy.savedMessage("new"))
    }

    @Test
    fun disabledBadgeLabelIsShort() {
        assertEquals("выкл", SkillsAdminPolicy.DISABLED_BADGE)
    }

    @Test
    fun adminActionsAvailableForAnyLoadedSkill() {
        // Toggle/delete не зависят от контента; edit — от загруженного SKILL.md.
        val skill = SkillItem("mars-toolkit", "RAG-память", "devops", disabled = true)
        assertTrue(SkillsAdminPolicy.canToggle(skill))
        assertTrue(SkillsAdminPolicy.canDelete(skill))
        assertTrue(SkillsAdminPolicy.canEditContent(skill))
        assertNull(SkillsAdminPolicy.saveBlockReason(name = "x", content = "c", category = ""))
        assertEquals("Неверное имя скилла", SkillsAdminPolicy.saveBlockReason(name = "../x", content = "c", category = ""))
        assertEquals("Содержимое не может быть пустым", SkillsAdminPolicy.saveBlockReason(name = "x", content = " ", category = ""))
        assertEquals("Неверная категория", SkillsAdminPolicy.saveBlockReason(name = "x", content = "c", category = "a/b"))
    }
}
