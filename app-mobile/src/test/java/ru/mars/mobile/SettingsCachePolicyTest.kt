package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Очистить кэш» на экране «Настройки» обязана стирать РЕАЛЬНЫЕ локальные кэши
 * приложения (офлайн-копии диалогов/задач/проектов/знаний и очередь отправки в
 * SharedPreferences + временные файлы cacheDir), а не только cacheDir, как было
 * до исправления аудита. Набор имён берётся из единого источника
 * [AuthExitStores] — без дублей строк в двух местах.
 *
 * Гигиена: пользовательские plaintext-данные (черновики, история поиска музыки)
 * кэшем НЕ являются и очисткой кэша не стираются; credential устройства стирается
 * только явным logout ([LogoutFlow]), никогда — кнопкой кэша.
 */
class SettingsCachePolicyTest {

    @Test
    fun `cache set covers session, todos, projects, knowledge caches and pending queue`() {
        assertEquals(
            listOf(
                "mars-offline-cache",
                "mars_todos_offline",
                "mars_projects_offline",
                "mars_projects_list_offline",
                "mars_knowledge_offline",
                "mars_music_offline",
                "mars-pending-messages",
            ),
            SettingsCachePolicy.CACHE_PREFS_FILES,
        )
        assertEquals(
            "дубли означали бы двойную очистку одного файла",
            SettingsCachePolicy.CACHE_PREFS_FILES.size,
            SettingsCachePolicy.CACHE_PREFS_FILES.distinct().size,
        )
    }

    @Test
    fun `cache set is a subset of auth-exit prefs - single source of names`() {
        assertTrue(
            "имена кэшей обязаны происходить из AuthExitStores, без собственных строк",
            AuthExitStores.PREFS_FILES.containsAll(SettingsCachePolicy.CACHE_PREFS_FILES),
        )
        assertTrue(
            "кэш знаний — часть контракта выхода из авторизации (KnowledgeOfflineCache)",
            AuthExitStores.PREFS_FILES.contains(AuthExitStores.KNOWLEDGE_OFFLINE_PREFS),
        )
    }

    @Test
    fun `user data and credential are never cleared as cache`() {
        assertFalse(
            "черновики — набранный пользователем текст, не кэш",
            AuthExitStores.DRAFTS_PREFS in SettingsCachePolicy.CACHE_PREFS_FILES,
        )
        assertFalse(
            "история поиска музыки — данные пользователя, не кэш",
            AuthExitStores.MUSIC_HISTORY_PREFS in SettingsCachePolicy.CACHE_PREFS_FILES,
        )
        assertFalse(
            "credential стирается только явным logout, не очисткой кэша",
            CREDENTIAL_PREFS in SettingsCachePolicy.CACHE_PREFS_FILES ||
                CREDENTIAL_PREFS in AuthExitStores.PREFS_FILES,
        )
        assertTrue(SettingsCachePolicy.isUserDataFile(AuthExitStores.DRAFTS_PREFS))
        assertTrue(SettingsCachePolicy.isUserDataFile(AuthExitStores.MUSIC_HISTORY_PREFS))
        SettingsCachePolicy.CACHE_PREFS_FILES.forEach {
            assertFalse("кэш не является пользовательскими данными", SettingsCachePolicy.isUserDataFile(it))
        }
    }

    @Test
    fun `prefs file name is the on-disk shared_prefs xml`() {
        assertEquals("mars-offline-cache.xml", SettingsCachePolicy.prefsFileName("mars-offline-cache"))
        assertEquals("mars_todos_offline.xml", SettingsCachePolicy.prefsFileName(AuthExitStores.TODOS_OFFLINE_PREFS))
        assertEquals("shared_prefs", SettingsCachePolicy.SHARED_PREFS_DIR)
    }

    @Test
    fun `total size sums sources and ignores read errors`() {
        assertEquals(0L, SettingsCachePolicy.totalSizeBytes(emptyList()))
        assertEquals(300L, SettingsCachePolicy.totalSizeBytes(listOf(100L, 200L)))
        assertEquals(
            "отрицательный размер (файл не читается) не уменьшает итог",
            200L,
            SettingsCachePolicy.totalSizeBytes(listOf(-1L, 200L)),
        )
    }

    @Test
    fun `size formatting uses bytes, kilobytes and megabytes with one decimal`() {
        assertEquals("0 Б", SettingsCachePolicy.formatSize(0L))
        assertEquals("512 Б", SettingsCachePolicy.formatSize(512L))
        assertEquals("1023 Б", SettingsCachePolicy.formatSize(1023L))
        assertEquals("1 КБ", SettingsCachePolicy.formatSize(1024L))
        assertEquals("1 КБ", SettingsCachePolicy.formatSize(1536L))
        assertEquals("1.0 МБ", SettingsCachePolicy.formatSize(1024L * 1024L))
        assertEquals("1.5 МБ", SettingsCachePolicy.formatSize(1536L * 1024L))
        assertEquals("отрицательный размер показывается как ноль", "0 Б", SettingsCachePolicy.formatSize(-5L))
    }

    private companion object {
        /** Имя prefs-файла credential-стора (AndroidKeyStoreCredentialStore). */
        const val CREDENTIAL_PREFS = "mars.native.device.credential"
    }
}
