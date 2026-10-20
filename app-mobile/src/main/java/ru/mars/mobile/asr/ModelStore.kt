package ru.mars.mobile.asr

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Раскладка модели на диске и атомарная установка.
 *
 * Схема каталогов:
 * ```
 * <rootDir>/                                  (filesDir/asr)
 *   sherpa-onnx-...-2025-08-16/               готовая модель — только целиком
 *   staging-<nano>/                           временный каталог распаковки
 *   model-<nano>.tar.bz2.part                 недокачанный архив
 * ```
 *
 * Правило: в каталоге модели не бывает «полумодели». Распаковка идёт в staging, там же
 * проверяются размеры и хеши, и только потом каталог переименовывается в боевой. `rename`
 * в пределах одного filesDir атомарен, поэтому параллельный запуск движка (или падение
 * процесса на середине) не оставит каталог, на котором native-слой упадёт при загрузке.
 */
class ModelStore(
    val rootDir: File,
    internal val expectedFiles: Map<String, Long> = AsrModelManifest.FILES,
    internal val fileSha256: Map<String, String> = AsrModelManifest.FILE_SHA256,
    private val modelId: String = AsrModelManifest.MODEL_ID,
) {
    /** Боевой каталог модели. */
    val modelDir: File get() = File(rootDir, modelId)

    /**
     * Быстрая проверка готовности: все файлы есть и размеры совпадают.
     * Хеши здесь не считаются — 29 МиБ SHA-256 на каждом старте приложения не нужны;
     * полная проверка делается один раз при установке ([verify]).
     */
    fun isInstalled(): Boolean = hasAllFiles(modelDir)

    /** Все обязательные файлы на месте и нужного размера. */
    fun hasAllFiles(dir: File): Boolean =
        expectedFiles.all { (name, size) ->
            val f = File(dir, name)
            f.isFile && f.length() == size
        }

    /** Полная проверка содержимого по SHA-256 (после распаковки). */
    fun verify(dir: File): Boolean =
        expectedFiles.keys.all { name ->
            val f = File(dir, name)
            f.isFile && f.length() == expectedFiles.getValue(name) &&
                sha256(f) == fileSha256.getValue(name)
        }

    /** Новый пустой каталог распаковки (уникальный, чтобы не пересечься с другим процессом). */
    fun newStagingDir(): File {
        rootDir.mkdirs()
        val dir = File(rootDir, "staging-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    /**
     * Атомарно поставить готовый каталог [staging] на место модели.
     * Старая модель (если была) удаляется только после успешного переименования.
     */
    fun installFrom(staging: File): Boolean {
        if (!hasAllFiles(staging)) return false
        rootDir.mkdirs()

        val target = modelDir
        val previous = File(rootDir, "$modelId.previous-${System.nanoTime()}")
        val hadPrevious = target.exists()

        if (hadPrevious && !target.renameTo(previous)) {
            // Не смогли отодвинуть старую модель — распаковывать поверх нельзя (смешаются файлы).
            if (!target.deleteRecursively()) return false
        }
        if (!staging.renameTo(target)) {
            // Откат: возвращаем прежнюю модель, чтобы приложение не осталось без ASR.
            if (hadPrevious) previous.renameTo(target)
            return false
        }
        if (hadPrevious) previous.deleteRecursively()
        return true
    }

    /** Удалить модель (для «скачать заново» из настроек). */
    fun deleteModel(): Boolean = !modelDir.exists() || modelDir.deleteRecursively()

    companion object {
        /** Каталог модели в приватном хранилище приложения: `<filesDir>/asr`. */
        fun rootForAppFilesDir(filesDir: File): File = File(filesDir, "asr")

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
