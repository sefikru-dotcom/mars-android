package ru.mars.mobile

/**
 * Реакция процесса на системные сигналы нехватки памяти — чистая JVM-политика
 * (константы ComponentCallbacks2 продублированы значениями, чтобы не тянуть
 * android.content в юнит-тесты; цифры сверены с документацией API).
 *
 * Хост: [MarsApplication].onTrimMemory/onLowMemory → при [shouldClearCaches]
 * чистятся кэши процесса (битмап-кэш ImageLoader — вызовом, сам ImageLoader
 * не меняется; его LruCache в любом случае ограничен бюджетом ImageLoaderPolicy).
 * Транскрипт дополнительно «подрезать» не нужно: его рост уже ограничен
 * [TranscriptCapPolicy] (cap сообщений и текстов), а view-дерево принадлежит
 * живым Activity и умирает вместе с ними.
 */
object MemoryTrimPolicy {

    // --- ComponentCallbacks2 (значения, не импорт) ---
    const val TRIM_MEMORY_RUNNING_MODERATE = 5
    const val TRIM_MEMORY_RUNNING_LOW = 10
    const val TRIM_MEMORY_RUNNING_CRITICAL = 15
    const val TRIM_MEMORY_UI_HIDDEN = 20
    const val TRIM_MEMORY_BACKGROUND = 40
    const val TRIM_MEMORY_MODERATE = 60
    const val TRIM_MEMORY_COMPLETE = 80

    /** Синтетический уровень для onLowMemory — «давление сильнее любого trim-уровня». */
    const val LOW_MEMORY_LEVEL = 100

    /**
     * Чистить кэши при уровне trim: давление в рантайме (RUNNING_LOW/RUNNING_CRITICAL)
     * и любой уровень «ушли с экрана/в фон» (>= UI_HIDDEN). RUNNING_MODERATE (5) —
     * ещё не повод: чистка кэшей на нём вызвала бы thrash миниатюр при каждом чихе.
     */
    fun shouldClearCaches(level: Int): Boolean =
        level == TRIM_MEMORY_RUNNING_LOW ||
            level == TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= TRIM_MEMORY_UI_HIDDEN
}
