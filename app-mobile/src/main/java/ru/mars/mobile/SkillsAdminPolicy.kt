package ru.mars.mobile

import ru.mars.network.model.SkillItem

/**
 * Чистые правила админки скиллов с телефона (без Android, тестируется на JVM).
 *
 * Серверные контракты hermes-webui api/route_skills.py:
 * - save (:464): имя нормализуется `strip().lower().replace(" ", "-")`, затем
 *   `""`/`/`/`..` → 400 «Invalid skill name»; категория с `/` или `..` → 400;
 * - delete (:495): необратимый `rmtree` каталога скилла — UI обязан требовать
 *   явное подтверждение ([deleteConfirmMessage]);
 * - toggle (:536): `{name, enabled}` переписывает список disabled в конфиге профиля.
 */
object SkillsAdminPolicy {

    /** Бейдж выключенного скилла в каталоге (поле `disabled` из GET /api/skills). */
    const val DISABLED_BADGE = "выкл"

    /** Та же нормализация, что на сервере (route_skills.py:473) — показываем имя как оно ляжет на диск. */
    fun normalizeName(raw: String): String = raw.trim().lowercase().replace(' ', '-')

    /** Зеркалит серверную валидацию имени: пусто, `/` или `..` → 400. */
    fun isNameValid(raw: String): Boolean {
        val name = normalizeName(raw)
        return name.isNotEmpty() && !name.contains('/') && !name.contains("..")
    }

    /** Зеркалит серверную валидацию категории (route_skills.py:477): `/` и `..` → 400. */
    fun isCategoryValid(raw: String): Boolean = !raw.contains('/') && !raw.contains("..")

    fun canToggle(skill: SkillItem): Boolean = skill.name.isNotBlank()

    fun canDelete(skill: SkillItem): Boolean = skill.name.isNotBlank()

    fun canEditContent(skill: SkillItem): Boolean = skill.name.isNotBlank()

    /** Почему сохранение заблокировано; null — можно отправлять. */
    fun saveBlockReason(name: String, content: String, category: String): String? = when {
        !isNameValid(name) -> "Неверное имя скилла"
        content.isBlank() -> "Содержимое не может быть пустым"
        !isCategoryValid(category.trim()) -> "Неверная категория"
        else -> null
    }

    /** Подпись кнопки toggle: действие, обратное текущему состоянию. */
    fun toggleLabel(currentlyEnabled: Boolean): String = if (currentlyEnabled) "Выключить" else "Включить"

    fun toggledMessage(name: String, enabled: Boolean): String =
        if (enabled) "Скилл «$name» включён" else "Скилл «$name» выключен"

    /** Текст админ-подтверждения удаления (rmtree на сервере необратим). */
    fun deleteConfirmMessage(name: String): String =
        "Удалить скилл «$name» с сервера? Каталог скилла будет стёрт безвозвратно."

    fun deletedMessage(name: String): String = "Скилл «$name» удалён"

    fun savedMessage(name: String): String = "Скилл «$name» сохранён"
}
