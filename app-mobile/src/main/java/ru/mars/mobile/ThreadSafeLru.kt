package ru.mars.mobile

/**
 * Минимальный потокобезопасный LRU-кэш для горячих путей рендера
 * (кэш разбора markdown в [MarkdownFormatter], шаблоны drawable в MarsTheme).
 *
 * Access-order [LinkedHashMap] под монитором: `get` тоже меняет порядок
 * обращений, поэтому и чтение, и запись синхронизированы. Парсинг markdown
 * планируется к выносу с UI-потока, значит кэш обязан быть thread-safe
 * уже сейчас — отсюда монитор, а не предположение об одном потоке.
 *
 * Намеренно без android.util.LruCache: этот класс живёт и в чистых JVM
 * unit-тестах (модуль тестируется без Robolectric).
 */
internal class ThreadSafeLru<K : Any, V : Any>(private val maxSize: Int) {

    init {
        require(maxSize > 0) { "maxSize must be positive: $maxSize" }
    }

    private val map = object : LinkedHashMap<K, V>(DEFAULT_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
    }

    @Synchronized
    fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = value
    }

    @Synchronized
    fun containsKey(key: K): Boolean = map.containsKey(key)

    @Synchronized
    fun size(): Int = map.size

    /** Ключи от старейшего к новейшему — для тестов и диагностики. */
    @Synchronized
    fun keysOldestFirst(): List<K> = map.keys.toList()

    @Synchronized
    fun clear() = map.clear()

    private companion object {
        const val DEFAULT_CAPACITY = 16
    }
}
