package ru.mars.mobile.tts

import android.content.Context
import android.os.StatFs
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import ru.mars.mobile.asr.ModelStore
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

/**
 * Установка русской TTS-модели на диск: HTTPS -> `.part` -> SHA-256 архива -> распаковка
 * ДЕРЕВОМ в staging -> проверка -> атомарная установка.
 *
 * ## Почему не [ru.mars.mobile.asr.ModelDownloader]
 * Он не подходит на роль «скачать и распаковать» именно из-за вложенности: его `unpack`
 * берёт `entry.name.substringAfterLast('/')` (плоский basename) и отбрасывает всё, чего нет
 * в `expectedFiles`, — то есть выкинул бы все 355 файлов `espeak-ng-data/`, а оставшиеся
 * сложил бы в один каталог, потеряв `lang/<группа>/<язык>` и `voices/!v/<вариант>`. Чинить его
 * нельзя: он рабочий для ASR (менять файлы `asr/` запрещено контрактом), а обойти
 * приватные `download`/`unpack` наследованием нельзя — класс `final`.
 *
 * Поэтому второй *политики* загрузки здесь нет, есть только транспорт:
 * - URL, размер и SHA-256 берутся из одного места — [OfflineTtsManifest] (не дублируются);
 * - проверка целостности, раскладка на диске, staging, откат и атомарное переименование —
 *   существующий [ModelStore] (он параметризуется манифестом и не знает про ASR);
 * - распаковка — своя, потому что дерево espeak-ng плоским списком не описать
 *   (см. `OfflineTtsManifest`), и она сохраняет относительные пути.
 *
 * Порядок шагов важен и совпадает с ASR-путём:
 * 1. качаем во временный `.part` (обрыв сети не оставляет валидный с виду архив);
 * 2. сверяем размер и SHA-256 архива — до этого содержимое недоверенное;
 * 3. распаковываем дерево в отдельный staging-каталог, отсекая path traversal;
 * 4. проверяем плоские файлы по хешам ([ModelStore.verify]) и дерево espeak по составу;
 * 5. переименовываем staging в боевой каталог ([ModelStore.installFrom]).
 *
 * Повторный вызов при уже установленной модели не ходит в сеть ([isInstalled]).
 * Сеть нужна ровно один раз; после установки синтез работает в авиарежиме.
 *
 * ## Одна модель, никаких «запасных»
 * int8-варианта в установке больше нет: в приложении ровно один голос — полноточная Ирина
 * ([OfflineTtsManifest.MODEL_ID]). Каталог `…-int8`, оставшийся от прежних версий, удаляется
 * точечно и только при точном опознании ([purgeLegacyModel]) — это не «зачистка каталога»,
 * а снятие ровно одного известного каталога.
 *
 * ## Место на диске
 * На пике существуют ОБЕ копии модели: архив (64 МиБ) и распакованное дерево в staging
 * (77,4 МиБ). Поэтому перед скачиванием проверяется [OfflineTtsManifest.PEAK_BYTES]
 * (≈149 МиБ): иначе телефон с 70 МиБ свободных молча качает 64 МиБ и падает на распаковке,
 * оставив за собой недокачанный архив. Проверка не блокирует установку, когда размер
 * свободного места неизвестен (`<= 0`).
 */
class OfflineTtsModel(
    private val store: ModelStore,
    private val archiveUrl: String = OfflineTtsManifest.ARCHIVE_URL,
    private val archiveSha256: String = OfflineTtsManifest.ARCHIVE_SHA256,
    private val archiveSize: Long = OfflineTtsManifest.ARCHIVE_SIZE,
    private val onProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
    private val log: (String) -> Unit = { android.util.Log.w(TAG, it) },
    /**
     * Свободно байт в приватном хранилище приложения. `<= 0` — неизвестно (тогда проверка
     * места не блокирует установку): на JVM-тестах `StatFs` отдаёт 0, и трактовать ноль как
     * «места нет» значило бы запретить установку в любом тесте.
     */
    private val freeBytes: () -> Long = { freeBytesIn(store.rootDir) },
    /**
     * Паспорт устаревшей int8-модели — по нему опознаётся каталог, который разрешено удалить.
     * Параметр, а не константа внутри: иначе проверку опознания нельзя было бы протестировать
     * без 18,5-мегабайтного файла.
     */
    private val legacy: LegacyModelSpec = LegacyModelSpec.fromManifest(),
) {
    /** Боевой каталог модели: `<filesDir>/tts/vits-piper-ru_RU-irina-medium`. */
    val modelDir: File get() = store.modelDir

    /** Каталог устаревшей int8-модели: `<filesDir>/tts/vits-piper-ru_RU-irina-medium-int8`. */
    val legacyModelDir: File get() = File(store.rootDir, legacy.id)

    /** Каталог словарей espeak-ng — аргумент `dataDir` для sherpa-onnx. */
    val espeakDir: File get() = File(modelDir, OfflineTtsManifest.ESPEAK_DIR)

    /** Путь к самому синтезатору (`.onnx`). */
    val modelFile: File get() = File(modelDir, OfflineTtsManifest.MODEL_FILE)

    /** Путь к `tokens.txt`. */
    val tokensFile: File get() = File(modelDir, OfflineTtsManifest.TOKENS_FILE)

    /**
     * Быстрая проверка готовности: плоские файлы нужного размера + дерево espeak на месте.
     * Хеши не считаются (полная проверка — один раз при установке), но файлы дерева
     * пересчитываются: 355 `stat` дешевле, чем упавший native-слой на полумодели.
     */
    fun isInstalled(): Boolean = store.isInstalled() && hasEspeakTree(modelDir)

    /**
     * Гарантировать модель на диске.
     *
     * Побочные действия, оба намеренные: снимается точно опознанный устаревший int8-каталог
     * ([purgeLegacyModel]) и проверяется свободное место ДО скачивания.
     *
     * @return `true` — модель установлена и проверена (или уже была); `false` — сеть, архив,
     *   место или права подвели. Повторного скачивания при `false` не будет до следующего
     *   вызова: решает хост.
     */
    @Synchronized
    fun ensure(): Boolean {
        // Устаревший int8-каталог снимается ДО любых проверок — в том числе когда полная
        // модель уже стоит: именно у этих устройств он и остался мёртвыми 36,6 МиБ.
        // Нет каталога — одна проверка `exists()`, на бюджете это не видно.
        purgeLegacyModel()

        if (isInstalled()) return true

        store.rootDir.mkdirs()
        // Место проверяется ДО сети: 64 МиБ архива, который заведомо не распакуется, — это
        // минуты трафика и заполненный раздел. Неизвестный размер (`<= 0`) не блокирует.
        val free = runCatching { freeBytes() }.getOrDefault(-1L)
        if (free in 1 until OfflineTtsManifest.PEAK_BYTES) {
            log(
                "not enough free space for model: $free < ${OfflineTtsManifest.PEAK_BYTES} bytes",
            )
            return false
        }

        val archive = File(store.rootDir, "tts-${System.nanoTime()}.tar.bz2.part")
        try {
            if (!download(archive)) {
                log("download failed: $archiveUrl")
                return false
            }
            // Дешёвая проверка до хеширования 64 МиБ: обрезанная загрузка отсекается сразу.
            if (archive.length() != archiveSize) {
                log("archive size mismatch: ${archive.length()} != $archiveSize")
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
                // Плоские файлы — по размеру и SHA-256 (тот же verify, что у ASR).
                if (!store.verify(staging)) {
                    log("unpacked files failed verification")
                    return false
                }
                if (!hasEspeakTree(staging)) {
                    log("espeak-ng-data tree is incomplete")
                    return false
                }
                if (!store.installFrom(staging)) {
                    log("install failed")
                    return false
                }
                return true
            } finally {
                // После успешного installFrom каталога уже нет — вызов безвреден.
                staging.deleteRecursively()
            }
        } catch (e: IOException) {
            log("model download error: ${e.message}")
            return false
        } catch (e: SecurityException) {
            log("model download denied: ${e.message}")
            return false
        } catch (e: Exception) {
            // Контракт [ensure] — «вернул false», а не «бросил наружу»: сюда попадают
            // NoSuchAlgorithmException из [ModelStore.sha256], IllegalArgumentException
            // на кривом URL и прочие редкие отказы. Ловим после IOException/SecurityException,
            // чтобы их сообщения не потерялись в общем тексте.
            log("model install error: ${e.message}")
            return false
        } finally {
            // Архив после установки не нужен: ещё 64 МиБ в filesDir — заметно на бюджете.
            archive.delete()
        }
    }

    /**
     * Удалить устаревший каталог int8-модели, если он ТОЧНО опознан.
     *
     * Правило узкое намеренно: под удаление попадает ровно один каталог с известным именем
     * в корне TTS-хранилища, и только если внутри лежит `.onnx` известного размера с
     * известным SHA-256. Всё остальное (чужое имя, другой/недокачанный файл, симлинк,
     * каталог вне корня) остаётся на месте — «на всякий случай почистить filesDir» здесь
     * запрещено: рядом живут ASR-модель, кэши и пользовательские данные.
     *
     * Идемпотентно: каталога нет — `true` (удалять нечего).
     *
     * @return `true` — каталога больше нет (удалён или отсутствовал); `false` — он есть, но
     *   не опознан как int8-модель либо ФС не дала удалить.
     */
    fun purgeLegacyModel(): Boolean {
        val dir = legacyModelDir
        if (!dir.exists()) return true
        if (!isLegacyModelDir(dir)) {
            log("legacy tts dir is not the known int8 model, keeping: ${dir.absolutePath}")
            return false
        }
        val removed = runCatching { dir.deleteRecursively() }.getOrDefault(false)
        if (removed) {
            log("removed legacy int8 tts model: ${dir.absolutePath}")
        } else {
            log("could not remove legacy int8 tts model: ${dir.absolutePath}")
        }
        return removed
    }

    /**
     * Каталог — это именно та int8-модель, которую ставили прежние версии.
     *
     * Проверки по порядку (первая же несошедшаяся — «не она»):
     * 1. имя каталога — [LegacyModelSpec.id], без разделителей и `..` и НЕ имя текущей модели;
     * 2. это каталог, а не файл;
     * 3. каталог — не симлинк, а канонический путь лежит ПРЯМО в корне TTS-хранилища;
     * 4. `.onnx` — обычный файл (не ссылка), нужного размера и SHA-256, то есть действительно
     *    скачан целиком.
     *
     * Про симлинки отдельно: `canonicalFile` разыменовывает ссылку на Linux (значит, и на
     * Android), но НЕ на Windows — там канонический путь остаётся путём ссылки. Поэтому
     * ссылка проверяется ещё и явно ([Files.isSymbolicLink]): без этого `deleteRecursively`
     * на Windows снёс бы содержимое каталога, на который указывает ссылка.
     */
    private fun isLegacyModelDir(dir: File): Boolean {
        val id = legacy.id
        if (id.isBlank() || id == OfflineTtsManifest.MODEL_ID) return false
        if (id.contains('/') || id.contains('\\') || id.contains("..")) return false
        if (!dir.isDirectory) return false
        if (Files.isSymbolicLink(dir.toPath())) return false

        val root = store.rootDir.canonicalFile
        val canonical = dir.canonicalFile
        // На Linux ссылка уже разыменована: чужой родитель отсекается здесь.
        if (canonical.parentFile != root) return false
        if (canonical.name != id) return false

        val onnx = File(canonical, legacy.fileName)
        if (!onnx.isFile || onnx.length() != legacy.size) return false
        if (Files.isSymbolicLink(onnx.toPath())) return false
        val actual = runCatching { ModelStore.sha256(onnx) }.getOrNull() ?: return false
        return actual.equals(legacy.sha256, ignoreCase = true)
    }

    /** Скачать [archiveUrl] в [dest]. `true` при HTTP 2xx и непустом файле. */
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
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: archiveSize
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

    /**
     * Распаковать tar.bz2 ЦЕЛИКОМ, сохранив дерево каталогов.
     *
     * Каталог верхнего уровня (`MODEL_ID/`) срезается: файлы модели должны лежать прямо в
     * каталоге модели, иначе `ModelStore.hasAllFiles` их не увидит. Всё остальное —
     * включая `espeak-ng-data/lang/<группа>/<язык>` — сохраняется как есть.
     *
     * Path traversal отсекается каноническим путём: запись обязана остаться внутри
     * staging-каталога, даже если архив прошёл проверку хеша (защита от `../` и абсолютных
     * имён). Число извлечённых файлов сверяется с манифестом — «распаковалось 12 из 359»
     * не должно выглядеть как успех.
     */
    private fun unpack(archive: File, dest: File): Boolean {
        var extracted = 0
        val destRoot = dest.canonicalFile
        try {
            // Вложенные `use`: на битом заголовке bz2 дескрипторы всё равно закрываются,
            // иначе на Windows открытый handle блокирует archive.delete() в finally.
            FileInputStream(archive).use { fis ->
                BZip2CompressorInputStream(BufferedInputStream(fis)).use { bz ->
                    TarArchiveInputStream(bz).use { tar ->
                        while (true) {
                            val entry = tar.nextEntry ?: break
                            val relative = relativeName(entry.name) ?: return false
                            if (relative.isEmpty()) continue
                            val target = File(dest, relative)
                            if (entry.isDirectory) {
                                target.mkdirs()
                                continue
                            }
                            target.parentFile?.mkdirs()
                            // Имя уже нормализовано, но проверяем канонический путь:
                            // запись обязана остаться внутри staging-каталога.
                            if (!target.canonicalFile.toPath().startsWith(destRoot.toPath())) {
                                log("refusing entry outside staging: ${entry.name}")
                                return false
                            }
                            FileOutputStream(target).use { out -> copyEntry(tar, out) }
                            extracted++
                        }
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
        if (extracted != OfflineTtsManifest.ARCHIVE_FILE_COUNT) {
            log("unexpected file count: $extracted != ${OfflineTtsManifest.ARCHIVE_FILE_COUNT}")
            return false
        }
        return true
    }

    /**
     * Имя записи архива относительно каталога модели.
     * @return `""` для самого каталога модели, `null` — запись небезопасна (абсолютный путь,
     *   `..`, выход за пределы `MODEL_ID/`).
     */
    private fun relativeName(rawName: String): String? {
        val name = rawName.removePrefix("./")
        if (name.isEmpty() || name.startsWith('/') || name.contains('\\')) return null
        val prefix = OfflineTtsManifest.MODEL_ID + "/"
        if (!name.startsWith(prefix)) {
            // Каталог верхнего уровня приходит отдельной записью `MODEL_ID/`.
            return if (name == OfflineTtsManifest.MODEL_ID) "" else null
        }
        val relative = name.removePrefix(prefix)
        if (relative.isEmpty()) return ""
        if (relative.split('/').any { it == ".." || it == "." }) return null
        return relative
    }

    /** Запись одного файла из архива — без прогресса (он относится к скачиванию). */
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
     * Дерево `espeak-ng-data` на месте: обязательные точки входа + ожидаемое число файлов.
     * Пофайловых хешей у дерева нет — целостность гарантирована SHA-256 всего архива.
     */
    fun hasEspeakTree(dir: File): Boolean {
        val root = File(dir, OfflineTtsManifest.ESPEAK_DIR)
        if (!root.isDirectory) return false
        if (OfflineTtsManifest.ESPEAK_REQUIRED.any { !File(root, it).exists() }) return false
        return countFiles(root) >= OfflineTtsManifest.ESPEAK_FILE_COUNT
    }

    private fun countFiles(dir: File): Int {
        val children = dir.listFiles() ?: return 0
        var count = 0
        for (child in children) {
            count += if (child.isDirectory) countFiles(child) else 1
        }
        return count
    }

    companion object {
        private const val TAG = "MarsOfflineTts"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val PROGRESS_STEP_BYTES = 1L * 1024 * 1024
        private const val USER_AGENT = "mars-android-tts/1.0"

        /**
         * Хранилище TTS-модели в приватном хранилище приложения: `<filesDir>/tts`.
         *
         * [ModelStore] параметризуется манифестом, поэтому ASR-раскладка (`filesDir/asr`,
         * свои `expectedFiles`) не затрагивается: один класс — два независимых каталога.
         */
        fun storeFor(context: Context): ModelStore = ModelStore(
            rootDir = File(context.applicationContext.filesDir, "tts"),
            expectedFiles = OfflineTtsManifest.FILES,
            fileSha256 = OfflineTtsManifest.FILE_SHA256,
            modelId = OfflineTtsManifest.MODEL_ID,
        )

        /** Установщик для приложения: `<filesDir>/tts` в приватном хранилище. */
        fun forApp(
            context: Context,
            onProgress: ((Long, Long) -> Unit)? = null,
        ): OfflineTtsModel = OfflineTtsModel(
            store = storeFor(context),
            onProgress = onProgress,
        )

        /**
         * Свободно байт на разделе с [dir]. `<= 0` — размер неизвестен: так же, как
         * `WarmUpDiagnostics.freeBytes`, «нет данных» отделено от «ноль байт».
         *
         * `StatFs` нужен только установщику; тесты подставляют свою функцию, поэтому ни один
         * тест не зависит от свободного места на машине сборки.
         */
        private fun freeBytesIn(dir: File): Long = try {
            dir.mkdirs()
            StatFs(dir.absolutePath).availableBytes
        } catch (t: Throwable) {
            -1L
        }
    }
}

/**
 * Паспорт устаревшей int8-модели: чем опознаётся каталог, который разрешено удалить.
 *
 * Отдельный тип, а не набор констант в коде удаления, ровно для тестируемости: проверка
 * «имя + размер + SHA-256» обязана быть покрыта тестом, а гонять в тесте настоящий
 * 18,5-мегабайтный `.onnx` незачем — подставляется паспорт на пару байт.
 */
data class LegacyModelSpec(
    /** Имя каталога в корне TTS-хранилища. */
    val id: String,
    /** Имя `.onnx` внутри каталога. */
    val fileName: String,
    /** Точный размер `.onnx`: у int8 он 18 579 710 байт, у полной модели — другой. */
    val size: Long,
    /** SHA-256 `.onnx`: каталог с тем же именем, но другим файлом не удаляется. */
    val sha256: String,
) {
    companion object {
        /** Паспорт настоящей int8-модели из [OfflineTtsManifest]. */
        fun fromManifest(): LegacyModelSpec = LegacyModelSpec(
            id = OfflineTtsManifest.LEGACY_MODEL_ID,
            fileName = OfflineTtsManifest.MODEL_FILE,
            size = OfflineTtsManifest.LEGACY_MODEL_FILE_BYTES,
            sha256 = OfflineTtsManifest.LEGACY_MODEL_FILE_SHA256,
        )
    }
}
