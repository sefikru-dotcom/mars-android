package ru.mars.mobile

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Тонкий Android-хост дискового кэша обложек: каталог, файлы, атомарная запись.
 *
 * Вся арифметика (ключи, TTL, бюджет, порядок вытеснения) живёт в чистой
 * [ImageDiskCachePolicy] и покрыта JVM-тестами; здесь только ввод-вывод, который
 * в JVM-тестах не проверить.
 *
 * Почему каталог — `cacheDir/covers`, а не `filesDir`: обложки восстанавливаются из сети
 * в любой момент, это кэш по смыслу, и система вправе вычистить его при нехватке места.
 * `cacheDir` даёт нам это бесплатно, а «Очистить кэш» в настройках удаляет его целиком
 * (`cacheDir.deleteRecursively()`), то есть дисковый кэш обложек попадает под штатную
 * очистку без отдельной кнопки.
 *
 * Почему запись через временный файл + `renameTo`: обрыв записи (убитый процесс, кончилось
 * место) не должен оставлять в кэше недописанный файл. `rename` в пределах одной ФС атомарен, поэтому
 * читатель видит либо старый файл целиком, либо новый целиком, но никогда — обрезанный.
 *
 * Приватность: обложки — публичные картинки с сервера музыки, персональных данных в них
 * нет. Поэтому выход из аккаунта диск-кэш НЕ стирает: это осознанное решение, а не
 * забывчивость. Стирается он только штатной очисткой кэша и системой.
 *
 * Потокобезопасность: индекс каталога и файловые операции сериализованы одним локом.
 * Чтение обложки — короткая операция (десятки КБ), а выигрыш от параллельных чтений
 * с флеш-памяти на порядок меньше стоимости ошибки в учёте бюджета.
 */
internal object ImageDiskCache {

    private const val TMP_SUFFIX = ".tmp"

    @Volatile
    private var directory: File? = null

    private val lock = Any()

    /** Индекс `sha256(url)` → файл. Строится сканированием каталога один раз за процесс. */
    private val index = ConcurrentHashMap<String, File>()

    /** Индекс уже построен по текущему содержимому каталога. */
    private var indexReady = false

    /** Суммарный размер файлов в индексе; ведётся инкрементально, чтобы не сканировать. */
    private var totalBytes = 0L

    /**
     * Привязывает кэш к процессу. Идемпотентно и дешёво после первого вызова: вызывается
     * из [MarsApplication.onCreate], раньше любого экрана, который грузит обложки.
     * Без вызова (unit-тесты, инструментальные сценарии без Application) кэш просто
     * выключен — [read] вернёт null, [write] ничего не сделает, загрузка идёт как раньше.
     */
    fun attach(context: Context) {
        if (directory != null) return
        synchronized(lock) {
            if (directory != null) return
            val dir = File(context.applicationContext.cacheDir, ImageDiskCachePolicy.DIRECTORY_NAME)
            if (!dir.exists() && !dir.mkdirs()) return
            directory = dir
            rebuildIndexLocked(dir)
        }
    }

    /**
     * Байты обложки из кэша или null. Протухшая/битая запись удаляется здесь же:
     * TTL проверяется на чтении, поэтому устаревшая картинка не покажется даже если
     * вытеснение по бюджету ещё не запускалось.
     */
    fun read(url: String): ByteArray? {
        val dir = directory ?: return null
        ensureIndex(dir)
        val key = ImageDiskCachePolicy.baseKey(url)
        val file = index[key] ?: return null
        synchronized(lock) {
            if (!file.isFile) {
                forgetLocked(key)
                return null
            }
            val now = System.currentTimeMillis()
            val size = file.length()
            val entry = ImageDiskCacheEntry(file.name, size, file.lastModified())
            if (ImageDiskCachePolicy.isExpired(entry, now)) {
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
     * Кладёт сетевой ответ на диск. Отказ по размеру ([ImageDiskCachePolicy.shouldStore])
     * молчаливый: кэш — оптимизация, а не обязательство, и ломать показ обложки из-за
     * невозможности её закэшировать нельзя.
     */
    fun write(url: String, contentType: String?, bytes: ByteArray) {
        val dir = directory ?: return
        if (!ImageDiskCachePolicy.shouldStore(bytes.size.toLong())) return
        // «Очистить кэш» в настройках удаляет cacheDir целиком — каталог может исчезнуть
        // под работающим процессом, поэтому восстанавливаем его перед записью.
        if (!dir.isDirectory && !dir.mkdirs()) return
        ensureIndex(dir)
        val name = ImageDiskCachePolicy.fileName(url, contentType)
        val key = ImageDiskCachePolicy.baseKey(url)
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
                    // fsync до rename: иначе после внезапного выключения rename может
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
            evictLocked(ImageDiskCacheEntry(name, bytes.size.toLong(), System.currentTimeMillis()))
        }
    }

    /**
     * Удаляет запись из кэша. Нужен для испорченных файлов: байты, которые не удалось
     * декодировать, хранить бессмысленно — они будут ломать показ до истечения TTL.
     */
    fun remove(url: String) {
        val dir = directory ?: return
        ensureIndex(dir)
        val key = ImageDiskCachePolicy.baseKey(url)
        val file = index[key] ?: return
        synchronized(lock) {
            val size = file.length()
            file.delete()
            index.remove(key)
            totalBytes -= size
        }
    }

    /** Сканирование каталога при первом обращении: индекс в памяти, а не `listFiles()` на каждый показ. */
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
        val files = dir.listFiles().orEmpty()
        for (file in files) {
            if (!file.isFile) continue
            if (file.name.endsWith(TMP_SUFFIX)) {
                // Хвост прерванной записи: данных в нём нет доверия, место он занимает.
                file.delete()
                continue
            }
            if (file.name.length <= ImageDiskCachePolicy.KEY_LENGTH) continue
            val key = file.name.substring(0, ImageDiskCachePolicy.KEY_LENGTH)
            index[key] = file
            totalBytes += file.length()
        }
        indexReady = true
    }

    /**
     * Вытеснение по бюджету. Список записей собирается только когда бюджет реально
     * превышен (или записей больше потолка): в норме запись обложки не стоит ни одного
     * лишнего `stat`, а каталог из сотен файлов не сканируется на каждый кадр.
     */
    private fun evictLocked(incoming: ImageDiskCacheEntry) {
        if (totalBytes <= ImageDiskCachePolicy.BUDGET_BYTES &&
            index.size <= ImageDiskCachePolicy.MAX_ENTRIES
        ) {
            return
        }
        val entries = index.values.map { file ->
            ImageDiskCacheEntry(file.name, file.length(), file.lastModified())
        }
        val victims = ImageDiskCachePolicy.selectEvictions(entries, incoming)
        for (victim in victims) {
            if (victim == incoming.fileName) continue
            val key = victim.substring(0, ImageDiskCachePolicy.KEY_LENGTH)
            val file = index[key] ?: continue
            deleteLocked(file, key, file.length())
        }
        // Пересчёт после удалений: инкрементальный учёт мог разойтись с диском
        // (файл удалили извне, запись не удалась), а бюджет — жёсткое обещание.
        totalBytes = index.values.sumOf { it.length() }
    }

    private fun deleteLocked(file: File, key: String, sizeBytes: Long) {
        file.delete()
        index.remove(key)
        totalBytes -= sizeBytes
    }

    private fun forgetLocked(key: String) {
        index.remove(key)
        totalBytes = index.values.sumOf { it.length() }
    }
}
