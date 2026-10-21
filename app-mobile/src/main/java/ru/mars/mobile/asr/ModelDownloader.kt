package ru.mars.mobile.asr

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Загрузка модели при первом запуске: HTTPS -> `.tar.bz2.part` -> проверка SHA-256 ->
 * распаковка в staging -> проверка файлов -> атомарная установка.
 *
 * Почему так, а не «скачать пять файлов»: у архива релиза один хеш, и проверка до
 * распаковки отсекает битую/подменённую загрузку целиком. Порядок шагов важен:
 * 1. качаем во временный `.part` (обрыв сети не оставляет валидный с виду архив);
 * 2. сверяем SHA-256 архива — до этого момента содержимое считается недоверенным;
 * 3. распаковываем в отдельный staging-каталог, берём только ожидаемые имена файлов;
 * 4. сверяем размеры и SHA-256 каждого файла;
 * 5. переименовываем staging в боевой каталог (см. [ModelStore.installFrom]).
 *
 * Повторный вызов при уже установленной модели не ходит в сеть ([isAvailable]).
 */
class ModelDownloader(
    private val store: ModelStore,
    private val archiveUrl: String = AsrModelManifest.ARCHIVE_URL,
    private val archiveSha256: String = AsrModelManifest.ARCHIVE_SHA256,
    private val onProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
    private val log: (String) -> Unit = { android.util.Log.w(TAG, it) },
) : AsrModelSource {

    override val modelDir: File get() = store.modelDir

    override fun isAvailable(): Boolean = store.isInstalled()

    override fun ensure(): Boolean = ensureModel()

    /**
     * Гарантировать модель на диске.
     * @return true — модель установлена и проверена (или уже была); false — сеть/архив/диск
     *   подвели; приложение должно остаться на серверном ASR.
     */
    fun ensureModel(): Boolean {
        if (store.isInstalled()) return true

        store.rootDir.mkdirs()
        val archive = File(store.rootDir, "model-${System.nanoTime()}.tar.bz2.part")
        try {
            if (!download(archive)) {
                log("download failed: $archiveUrl")
                return false
            }
            val actual = ModelStore.sha256(archive)
            if (!actual.equals(archiveSha256, ignoreCase = true)) {
                log("archive checksum mismatch: expected $archiveSha256, got $actual")
                return false
            }

            val staging = store.newStagingDir()
            try {
                if (!unpack(archive, staging)) {
                    log("unpack failed")
                    return false
                }
                if (!store.verify(staging)) {
                    log("unpacked files failed verification")
                    return false
                }
                if (!store.installFrom(staging)) {
                    log("install failed")
                    return false
                }
                return true
            } finally {
                staging.deleteRecursively()
            }
        } catch (e: IOException) {
            log("model download error: ${e.message}")
            return false
        } catch (e: SecurityException) {
            log("model download denied: ${e.message}")
            return false
        } finally {
            // Архив после установки не нужен: 23 МиБ в filesDir — заметно на бюджет телефона.
            archive.delete()
        }
    }

    /** Скачать [archiveUrl] в [dest]. true при HTTP 2xx и полностью записанном файле. */
    private fun download(dest: File): Boolean {
        val connection = URL(archiveUrl).openConnection()
        try {
            if (connection is HttpURLConnection) {
                // GitHub Releases отвечает 302 на objects.githubusercontent.com.
                connection.instanceFollowRedirects = true
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "application/octet-stream")
                val code = connection.responseCode
                if (code !in 200..299) {
                    log("http $code for $archiveUrl")
                    return false
                }
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            connection.getInputStream().use { input ->
                FileOutputStream(dest).use { output ->
                    copy(input, output, total)
                }
            }
            return dest.isFile && dest.length() > 0
        } finally {
            if (connection is HttpURLConnection) connection.disconnect()
        }
    }

    private fun copy(input: InputStream, output: FileOutputStream, total: Long) {
        val buffer = ByteArray(64 * 1024)
        var downloaded = 0L
        var lastReported = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            downloaded += read
            val callback = onProgress
            // Не дёргаем UI на каждый 64-КБ блок: прогресс нужен примерно раз в мегабайт.
            if (callback != null && downloaded - lastReported >= PROGRESS_STEP_BYTES) {
                lastReported = downloaded
                callback(downloaded, total)
            }
        }
        output.flush()
        onProgress?.invoke(downloaded, total)
    }

    /** Запись одного файла из архива — без прогресса (он относится к скачиванию, не к распаковке). */
    private fun copyEntry(input: InputStream, output: FileOutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
        output.flush()
    }

    /**
     * Распаковать tar.bz2, оставив только файлы из манифеста.
     *
     * Имя берётся как basename: в архиве всё лежит в одном каталоге модели, а вложенность
     * нам не нужна. Так же отсекается path traversal (`../`) — записать что-то вне staging
     * из архива нельзя, даже если он прошёл проверку хеша.
     */
    private fun unpack(archive: File, dest: File): Boolean {
        var extracted = 0
        try {
            BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive))).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val name = entry.name.substringAfterLast('/')
                        if (name !in store.expectedFiles) continue
                        val expected = store.expectedFiles.getValue(name)
                        if (entry.size != expected) {
                            log("unexpected size for $name: ${entry.size} != $expected")
                            return false
                        }
                        val target = File(dest, name)
                        // Имя — basename без разделителей, но проверяем канонический путь:
                        // запись обязана остаться внутри staging-каталога.
                        if (target.canonicalFile.parentFile != dest.canonicalFile) return false
                        FileOutputStream(target).use { out -> copyEntry(tar, out) }
                        extracted++
                    }
                }
            }
        } catch (e: IOException) {
            log("unpack io error: ${e.message}")
            return false
        } catch (e: RuntimeException) {
            // commons-compress бросает IllegalArgumentException на битом bz2-заголовке.
            log("unpack error: ${e.message}")
            return false
        }
        return extracted > 0 && store.hasAllFiles(dest)
    }

    companion object {
        private const val TAG = "MarsAsrModel"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val PROGRESS_STEP_BYTES = 1L * 1024 * 1024
        private const val USER_AGENT = "mars-android-asr/1.0"

        /** Загрузчик для приложения: `<filesDir>/asr` в приватном хранилище. */
        fun forApp(
            context: Context,
            onProgress: ((Long, Long) -> Unit)? = null,
        ): ModelDownloader = ModelDownloader(
            store = ModelStore(ModelStore.rootForAppFilesDir(context.applicationContext.filesDir)),
            onProgress = onProgress,
        )
    }
}
