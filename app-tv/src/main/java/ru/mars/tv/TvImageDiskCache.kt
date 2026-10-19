package ru.mars.tv

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Тонкий Android-хост дискового кэша обложек ТВ: каталог, файлы, атомарная запись.
 *
 * Вся арифметика (ключи, TTL, бюджет, порядок вытеснения) живёт в чистой
 * [TvImageDiskCachePolicy] и покрыта JVM-тестами; здесь только ввод-вывод, который
 * в JVM-тестах не проверить. Новых зависимостей нет: `java.io` + `ConcurrentHashMap`.
 *
 * Почему `cacheDir/covers`: обложки — кэш по смыслу (восстанавливаются из сети в любой
 * момент), и система вправе вычистить `cacheDir` при нехватке места. Для ТВ-приставки с
 * 4–8 ГБ флеш-памяти это важно: мы не занимаем `filesDir` тем, что можно скачать заново.
 *
 * Почему запись через временный файл + `renameTo`: обрыв записи (выдернули питание,
 * кончилось место) не должен оставлять недописанный файл. `rename` в пределах одной ФС
 * атомарен, поэтому читатель видит либо старый файл целиком, либо новый целиком.
 *
 * Приватность: обложки — публичные картинки с сервера музыки, персональных данных в них
 * нет. Поэтому выход из аккаунта диск-кэш НЕ стирает — осознанное решение: стирается он
 * только штатной очисткой кэша и системой.
 *
 * Потокобезопасность: в ТВ-клиенте загрузки идут одним фоновым [java.util.concurrent.Executor],
 * но лок всё равно один — инвариант учёта бюджета не должен зависеть от того, сколько
 * потоков когда-нибудь начнут звать [read]/[write].
 */
internal object TvImageDiskCache {

    private const val TMP_SUFFIX = ".tmp"

    @Volatile
    private var directory: File? = null

    private val lock = Any()

    /** Индекс `sha256(url)` -> файл; строится сканированием каталога один раз за процесс. */
    private val index = ConcurrentHashMap<String, File>()

    /** Индекс уже построен по текущему содержимому каталога. */
    private var indexReady = false

    /** Суммарный размер файлов в индексе; ведётся инкрементально, чтобы не сканировать. */
    private var totalBytes = 0L

    /**
     * Привязывает кэш к процессу. Вызывается из [TvMusicActivity.onCreate] — единственного
     * экрана, который показывает обложки (отдельного Application-класса в app-tv нет,
     * а плодить его ради каталога кэша незачем). Идемпотентно.
     *
     * Без вызова кэш просто выключен: [read] вернёт null, [write] ничего не сделает,
     * загрузка идёт как раньше (память -> сеть). Кэш — оптимизация, он не имеет права
     * ломать показ обложки.
     */
    fun attach(context: Context) {
        if (directory != null) return
        synchronized(lock) {
            if (directory != null) return
            val dir = File(context.applicationContext.cacheDir, TvImageDiskCachePolicy.DIRECTORY_NAME)
            if (!dir.exists() && !dir.mkdirs()) return
            directory = dir
            rebuildIndexLocked(dir)
        }
    }

    /**
     * Байты обложки из кэша или null. Протухшая/битая запись удаляется здесь же: TTL
     * проверяется на чтении, поэтому устаревшая картинка не покажется даже если вытеснение
     * по бюджету ещё не запускалось (каталог не превысил лимит — вытеснять нечего).
     */
    fun read(url: String): ByteArray? {
        val dir = directory ?: return null
        ensureIndex(dir)
        val key = TvImageDiskCachePolicy.baseKey(url)
        val file = index[key] ?: return null
        synchronized(lock) {
            if (!file.isFile) {
                index.remove(key)
                totalBytes = index.values.sumOf { it.length() }
                return null
            }
            val now = System.currentTimeMillis()
            val size = file.length()
            val entry = TvImageDiskCacheEntry(file.name, size, file.lastModified())
            if (TvImageDiskCachePolicy.isExpired(entry, now)) {
                deleteLocked(file, key, size)
                return null
            }
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes == null) {
                deleteLocked(file, key, size)
                return null
            }
            // Время последнего чтения — основа LRU: обновляем mtime, чтобы политика
            // вытесняла давно не показанные обложки, а не случайные.
            file.setLastModified(now)
            return bytes
        }
    }

    /**
     * Кладёт сетевой ответ на диск. Отказ по размеру ([TvImageDiskCachePolicy.shouldStore])
     * молчаливый: кэш — оптимизация, а не обязательство.
     */
    fun write(url: String, contentType: String?, bytes: ByteArray) {
        val dir = directory ?: return
        if (!TvImageDiskCachePolicy.shouldStore(bytes.size.toLong())) return
        // Штатная очистка кэша удаляет cacheDir целиком — каталог может исчезнуть
        // под работающим процессом, поэтому восстанавливаем его перед записью.
        if (!dir.isDirectory && !dir.mkdirs()) return
        ensureIndex(dir)
        val name = TvImageDiskCachePolicy.fileName(url, contentType)
        val key = TvImageDiskCachePolicy.baseKey(url)
        synchronized(lock) {
            val target = File(dir, name)
            val previousBytes = target.takeIf { it.isFile }?.length() ?: 0L
            val tmp = try {
                File.createTempFile(name + "-", TMP_SUFFIX, dir)
            } catch (e: IOException) {
                return
            }
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(bytes)
                    out.flush()
                    // fsync до rename: иначе после выдёргивания питания rename может
                    // оказаться на диске раньше данных, и в кэше останется нулевой файл.
                    out.fd.sync()
                }
            } catch (e: IOException) {
                tmp.delete()
                return
            }
            if (!tmp.renameTo(target)) {
                // На части ФС rename поверх существующего файла запрещён — убираем цель
                // и пробуем ещё раз; не вышло — временный файл не оставляем.
                val replaced = target.exists() && target.delete() && tmp.renameTo(target)
                if (!replaced) {
                    tmp.delete()
                    return
                }
            }
            index[key] = target
            totalBytes += bytes.size - previousBytes
            evictLocked(name, bytes.size.toLong())
        }
    }

    /**
     * Удаляет запись. Нужен для испорченных файлов: байты, которые не декодировались,
     * хранить бессмысленно — они ломали бы показ до истечения TTL.
     */
    fun remove(url: String) {
        val dir = directory ?: return
        ensureIndex(dir)
        val key = TvImageDiskCachePolicy.baseKey(url)
        val file = index[key] ?: return
        synchronized(lock) {
            deleteLocked(file, key, file.length())
        }
    }

    /** Сканирование каталога при первом обращении: индекс в памяти, а не `listFiles()` на каждый кадр. */
    private fun ensureIndex(dir: File) {
        if (indexReady) return
        synchronized(lock) {
            if (indexReady) return
            rebuildIndexLocked(dir)
        }
    }

    private fun rebuildIndexLocked(dir: File) {
        index.clear()
        totalBytes = 0L
        for (file in dir.listFiles().orEmpty()) {
            if (!file.isFile) continue
            if (file.name.endsWith(TMP_SUFFIX)) {
                // Хвост прерванной записи: данных в нём нет доверия, место он занимает.
                file.delete()
                continue
            }
            if (file.name.length <= TvImageDiskCachePolicy.KEY_LENGTH) continue
            val key = file.name.substring(0, TvImageDiskCachePolicy.KEY_LENGTH)
            index[key] = file
            totalBytes += file.length()
        }
        indexReady = true
    }

    /**
     * Вытеснение по бюджету. Список записей собирается только когда бюджет реально превышен
     * (или записей больше потолка): в норме запись обложки не стоит ни одного лишнего `stat`,
     * а каталог из сотен файлов не сканируется на каждый показ.
     */
    private fun evictLocked(incomingFileName: String, incomingSizeBytes: Long) {
        if (totalBytes <= TvImageDiskCachePolicy.BUDGET_BYTES &&
            index.size <= TvImageDiskCachePolicy.MAX_ENTRIES
        ) {
            return
        }
        val entries = index.values.map { file ->
            TvImageDiskCacheEntry(file.name, file.length(), file.lastModified())
        }
        val victimNames = TvImageDiskCachePolicy.selectEvictions(
            entries,
            TvImageDiskCacheEntry(incomingFileName, incomingSizeBytes, System.currentTimeMillis()),
        )
        for (victim in victimNames) {
            if (victim == incomingFileName) continue
            val key = victim.substring(0, TvImageDiskCachePolicy.KEY_LENGTH)
            val file = index[key] ?: continue
            deleteLocked(file, key, file.length())
        }
        // Пересчёт после удалений: инкрементальный учёт мог разойтись с диском
        // (файл удалили извне, удаление не удалось), а бюджет — жёсткое обещание.
        totalBytes = index.values.sumOf { it.length() }
    }

    private fun deleteLocked(file: File, key: String, sizeBytes: Long) {
        file.delete()
        index.remove(key)
        totalBytes -= sizeBytes
    }
}
