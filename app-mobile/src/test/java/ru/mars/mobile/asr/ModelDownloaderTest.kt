package ru.mars.mobile.asr

import java.io.File
import java.io.FileOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Тесты загрузчика модели ([ModelDownloader]) без сети: источник — `file://`-URL на
 * tar.bz2, собранный тут же через commons-compress.
 *
 * Проверяется порядок, от которого зависит «не убить приложение» на первом запуске:
 * checksum архива ДО распаковки, отказ при несовпадении, отсутствие полумодели и
 * временных файлов после любой ошибки, и главное — повторный вызов не качает снова.
 */
class ModelDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sizes = linkedMapOf("tokens.txt" to 3L, "encoder.onnx" to 4L)

    // Константы, а не чтение файлов: поле создаётся до TemporaryFolder (см. ModelStoreTest).
    private val hashes = linkedMapOf(
        "tokens.txt" to "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        "encoder.onnx" to "4c8a43980498636e9c1d1595fa5d115af7937c2422dfe68a2520a52b7a5fb4de",
    )

    private fun store(): ModelStore = ModelStore(tmp.root, sizes, hashes, modelId = "test-model")

    private fun archive(
        files: Map<String, ByteArray> = linkedMapOf(
            "tokens.txt" to "abc".toByteArray(),
            "encoder.onnx" to "defg".toByteArray(),
        ),
        topDir: String = "test-model",
        name: String = "model.tar.bz2",
    ): File {
        val target = File(tmp.root, name)
        FileOutputStream(target).use { fos ->
            BZip2CompressorOutputStream(fos).use { bz ->
                TarArchiveOutputStream(bz).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    files.forEach { (file, bytes) ->
                        val entry = TarArchiveEntry("$topDir/$file")
                        entry.size = bytes.size.toLong()
                        tar.putArchiveEntry(entry)
                        tar.write(bytes)
                        tar.closeArchiveEntry()
                    }
                }
            }
        }
        return target
    }

    private fun downloader(
        store: ModelStore = store(),
        archiveFile: File,
        expectedSha: String = ModelStore.sha256(archiveFile),
        progress: ((Long, Long) -> Unit)? = null,
    ): ModelDownloader = ModelDownloader(
        store = store,
        archiveUrl = archiveFile.toURI().toString(),
        archiveSha256 = expectedSha,
        onProgress = progress,
        log = {},
    )

    @Test
    fun installsModelFromArchive() {
        val store = store()
        val archive = archive()
        assertTrue(downloader(store, archive).ensureModel())

        assertTrue(store.isInstalled())
        assertEquals("abc", File(store.modelDir, "tokens.txt").readText())
        assertEquals("defg", File(store.modelDir, "encoder.onnx").readText())
        assertTrue("временных файлов быть не должно: ${tmp.root.list()!!.toList()}", noTemporaryArtifacts(store))
    }

    @Test
    fun checksumMismatchLeavesNoModel() {
        val store = store()
        val archive = archive()
        assertFalse(downloader(store, archive, expectedSha = "0".repeat(64)).ensureModel())

        assertFalse(store.modelDir.exists())
        assertTrue(noTemporaryArtifacts(store))
    }

    @Test
    fun secondCallDoesNotDownloadAgain() {
        val store = store()
        val archive = archive()
        val downloader = downloader(store, archive)
        assertTrue(downloader.ensureModel())

        // Источник исчез: если бы загрузчик пошёл в сеть, он бы упал.
        assertTrue(archive.delete())
        assertTrue(downloader.ensureModel())
        assertTrue(store.isInstalled())
    }

    @Test
    fun archiveWithoutRequiredFileFails() {
        val store = store()
        val partial = archive(files = linkedMapOf("tokens.txt" to "abc".toByteArray()))
        assertFalse(downloader(store, partial).ensureModel())

        assertFalse(store.modelDir.exists())
        assertTrue(noTemporaryArtifacts(store))
    }

    @Test
    fun fileWithRightSizeButWrongContentFails() {
        val store = store()
        val tampered = archive(
            files = linkedMapOf(
                "tokens.txt" to "abc".toByteArray(),
                "encoder.onnx" to "XXXX".toByteArray(),
            ),
        )
        assertFalse(downloader(store, tampered).ensureModel())
        assertFalse(store.modelDir.exists())
        assertTrue(noTemporaryArtifacts(store))
    }

    @Test
    fun corruptedArchiveFails() {
        val store = store()
        val broken = File(tmp.root, "broken.tar.bz2")
        broken.writeBytes(ByteArray(2048) { 0x42 })
        assertFalse(downloader(store, broken).ensureModel())
        assertFalse(store.modelDir.exists())
        assertTrue(noTemporaryArtifacts(store))
    }

    @Test
    fun missingSourceFails() {
        val store = store()
        val missing = File(tmp.root, "nope.tar.bz2")
        assertFalse(downloader(store, missing, expectedSha = "0".repeat(64)).ensureModel())
        assertFalse(store.modelDir.exists())
        assertTrue(noTemporaryArtifacts(store))
    }

    @Test
    fun progressIsReportedUpToArchiveSize() {
        val store = store()
        val archive = archive()
        val calls = ArrayList<Pair<Long, Long>>()
        assertTrue(
            downloader(store, archive, progress = { done, total -> calls.add(done to total) }).ensureModel(),
        )
        assertTrue(calls.isNotEmpty())
        assertEquals(archive.length(), calls.last().first)
        assertEquals(archive.length(), calls.last().second)
    }

    @Test
    fun isAvailableIsFalseBeforeAndTrueAfterInstall() {
        val store = store()
        val archive = archive()
        val dl = downloader(store, archive)
        assertFalse(dl.isAvailable())
        assertTrue(dl.ensure())
        assertTrue(dl.isAvailable())
        assertEquals(store.modelDir, dl.modelDir)
    }

    private fun noTemporaryArtifacts(store: ModelStore): Boolean =
        store.rootDir.listFiles()!!.none { it.name.endsWith(".part") || it.name.startsWith("staging-") }
}
