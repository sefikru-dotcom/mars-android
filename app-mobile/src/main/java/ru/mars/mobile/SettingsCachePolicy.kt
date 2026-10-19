package ru.mars.mobile

import java.util.Locale

/**
 * Экран «Настройки» → «Очистить кэш»: что считается локальным кэшем, как измерить
 * реально занятое место и как показать размер.
 *
 * Зачем политика вынесена из `SettingsActivity`: раньше очистка и размер считались
 * только по `cacheDir`, тогда как настоящие офлайн-кэши приложения живут в приватных
 * SharedPreferences-файлах (`mars-offline-cache`, кэши задач/проектов/знаний, очередь
 * неотправленных), а битмапы и разобранный Markdown — в process-кэшах. Кнопка «очистить»
 * не чистила ничего из этого, а размер строки «кэш» показывал число, не связанное с
 * реальными данными. Политика — чистая функция от имён файлов и размеров (JVM-тесты:
 * `SettingsCachePolicyTest`), Android-часть (обход диска, вызов `clearAll`) остаётся
 * в экране.
 *
 * Границы набора (продуктовый контракт):
 *  - кэш — производные данные, восстанавливаемые с сервера: офлайн-копии диалогов,
 *    задач, проектов, знаний и очередь неотправленных (её потеря допустима: это
 *    недоставленные черновики отправок, а очистка кэша — явное действие пользователя);
 *  - НЕ кэш — введённый пользователем текст (черновики диалогов, история поиска
 *    музыки): очистка кэша их не трогает, они стираются только выходом из аккаунта;
 *  - credential устройства не является кэшем и очищается исключительно явным
 *    logout через [LogoutFlow] — в этот набор он не входит никогда.
 *
 * Имена файлов берутся из единого источника [AuthExitStores] (никаких собственных
 * литералов): следующий офлайн-кэш добавляется туда и автоматически попадает
 * в оба набора — auth-exit и очистку кэша.
 */
object SettingsCachePolicy {

    /**
     * Приватные prefs-файлы, стираемые кнопкой «Очистить кэш». Порядок зафиксирован:
     * офлайн-кэши экранов, затем транспортная очередь.
     */
    val CACHE_PREFS_FILES: List<String> = listOf(
        AuthExitStores.OFFLINE_CACHE_PREFS,
        AuthExitStores.TODOS_OFFLINE_PREFS,
        AuthExitStores.PROJECTS_OFFLINE_PREFS,
        AuthExitStores.KNOWLEDGE_OFFLINE_PREFS,
        AuthExitStores.MUSIC_OFFLINE_PREFS,
        AuthExitStores.PENDING_QUEUE_PREFS,
    )

    /**
     * Пользовательские plaintext-данные: очистка кэша НЕ имеет права их стирать
     * (это не кэш, а введённый текст; их единственный путь очистки — выход из аккаунта).
     */
    fun isUserDataFile(prefsName: String): Boolean =
        prefsName == AuthExitStores.DRAFTS_PREFS || prefsName == AuthExitStores.MUSIC_HISTORY_PREFS

    /** Имя каталога приватных SharedPreferences внутри data-каталога приложения. */
    const val SHARED_PREFS_DIR = "shared_prefs"

    /** Имя файла SharedPreferences на диске (`shared_prefs/<имя>.xml`) — для честного замера размера. */
    fun prefsFileName(prefsName: String): String = "$prefsName.xml"

    /** Суммарный размер всех измеренных источников; отрицательные значения (ошибка чтения) не учитываются. */
    fun totalSizeBytes(sizes: List<Long>): Long = sizes.filter { it > 0 }.sum()

    /** Порог перехода на КБ. */
    const val KB: Long = 1024L

    /** Порог перехода на МБ. */
    const val MB: Long = 1024L * 1024L

    /**
     * Показ размера: «N Б» / «N КБ» / «X.X МБ». Отрицательный вход (ошибка измерения)
     * показывается как ноль — экран не должен пугать мусорными числами.
     * Локаль по умолчанию [Locale.US]: десятичная точка стабильна между устройствами.
     */
    fun formatSize(bytes: Long, locale: Locale = Locale.US): String = when {
        bytes < 0 -> formatSize(0, locale)
        bytes < KB -> "$bytes Б"
        bytes < MB -> "${bytes / KB} КБ"
        else -> String.format(locale, "%.1f МБ", bytes.toDouble() / MB)
    }
}
