package ru.mars.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-контракт экрана «Настройки» (паттерн [ChatClickPathContractTest]):
 * инструментального слоя в проекте нет, поэтому проводка исправлений аудита
 * закреплена чтением РЕАЛЬНОГО production-исходника SettingsActivity.
 *
 * Проверяется:
 *  - строка «Хост Марса» берётся из единого источника [ru.mars.network.api.MarsApi.BASE_URL],
 *    а не из дубля-литерала;
 *  - профиль/клиент строятся динамически ([SettingsInfoPolicy]) — без ложных
 *    «Stef (Stavropol)» и «HyperOS (POCO F5)» на чужом устройстве;
 *  - «Очистить кэш» чистит реальные хранилища: prefs-кэши из
 *    [SettingsCachePolicy.CACHE_PREFS_FILES] через фабрику [AuthExitStores]
 *    и process-кэши [ImageLoader]/[MarkdownFormatter], а не только cacheDir;
 *  - размер кэша измеряется по cacheDir И prefs-файлам, а не cacheDir-only;
 *  - credential очисткой кэша не стирается (единственный путь — [LogoutFlow] в performLogout);
 *  - диагностика соединения запускается повторно явной кнопкой.
 */
class SettingsSourceContractTest {

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

    private val settings by lazy { source("src/main/java/ru/mars/mobile/SettingsActivity.kt") }

    @Test
    fun `server row uses MarsApi BASE_URL and no duplicated literal`() {
        assertTrue(
            "строка «Хост Марса» обязана читаться из MarsApi.BASE_URL",
            settings.contains("MarsApi.BASE_URL"),
        )
        assertFalse(
            "второй источник правды о хосте в Settings запрещён",
            settings.contains("\"https://marscore.ru\""),
        )
    }

    @Test
    fun `profile and client rows are built dynamically`() {
        assertTrue(
            "«Клиент» — из реальных Build-полей через SettingsInfoPolicy.clientLabel",
            settings.contains("SettingsInfoPolicy.clientLabel("),
        )
        assertTrue(
            "«Профиль» — из реального credential через SettingsInfoPolicy.profileLabel",
            settings.contains("SettingsInfoPolicy.profileLabel("),
        )
        for (fake in listOf("Stef (Stavropol)", "HyperOS", "POCO")) {
            assertFalse(
                "захардкоженный текст «$fake» ложно показывается на любом другом устройстве",
                settings.contains(fake),
            )
        }
    }

    @Test
    fun `clear cache wipes prefs caches and process caches - not cacheDir only`() {
        assertTrue(
            "очистка обязана перебирать реальный набор prefs-кэшей",
            settings.contains("SettingsCachePolicy.CACHE_PREFS_FILES"),
        )
        assertTrue(
            "хранилища строятся единой фабрикой AuthExitStores (контракт очереди)",
            settings.contains("AuthExitStores.defaultStore("),
        )
        assertTrue(
            "очистка вызывает clearAll() на каждом кэше",
            settings.contains("clearAll()"),
        )
        assertTrue(
            "bitmap-кэш ImageLoader освобождается очисткой кэша",
            settings.contains("ImageLoader.trimMemoryCache()"),
        )
        assertTrue(
            "кэш разбора Markdown освобождается очисткой кэша",
            settings.contains("MarkdownFormatter.trimCache()"),
        )
    }

    @Test
    fun `cache size is measured over cacheDir AND prefs files`() {
        assertTrue(
            "размер кэша обязан учитывать prefs-файлы офлайн-кэшей",
            settings.contains("applicationInfo.dataDir") &&
                settings.contains("SettingsCachePolicy.SHARED_PREFS_DIR") &&
                settings.contains("prefsDir"),
        )
        assertTrue(
            "имя prefs-файла берётся из политики, без собственных строк",
            settings.contains("SettingsCachePolicy.prefsFileName("),
        )
        assertTrue(
            "итог считается политикой (ошибки чтения не ломают показ)",
            settings.contains("SettingsCachePolicy.totalSizeBytes("),
        )
        assertTrue(
            "показ размера форматируется политикой",
            settings.contains("SettingsCachePolicy.formatSize("),
        )
        // Строка кэша больше не обещает «треки»: дискового кэша треков в приложении нет.
        assertFalse(settings.contains("треков"))
    }

    @Test
    fun `cache clearing never touches the device credential`() {
        assertEquals(
            "прямое store.clear() в Settings запрещено: credential стирает только LogoutFlow",
            0,
            Regex("""store\.clear\(\)""").findAll(settings).count(),
        )
        assertTrue(
            "logout идёт существующим контрактом AuthExitStores.logoutFlow",
            settings.contains("AuthExitStores.logoutFlow("),
        )
    }

    @Test
    fun `diagnostics can be re-run explicitly`() {
        assertTrue(
            "кнопка повторной диагностики обязана существовать",
            settings.contains("Проверить ещё раз"),
        )
        assertTrue(
            "повторная диагностика вызывает тот же checkConnection()",
            settings.contains("setOnClickListener { checkConnection() }"),
        )
        assertTrue(
            "проба идёт на боевой хост, а не на localhost телефона",
            !settings.contains("127.0.0.1") && settings.contains("ServerHealthRepository("),
        )
        assertTrue(
            "на время повторной пробы строка статуса возвращается в состояние пробы",
            settings.contains("ConnectionStatusPolicy.PROBING"),
        )
    }
}
