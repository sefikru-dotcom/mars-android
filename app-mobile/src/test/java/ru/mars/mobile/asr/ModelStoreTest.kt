package ru.mars.mobile.asr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Тесты раскладки модели на диске и атомарной установки ([ModelStore]).
 *
 * Манифест подменяется маленьким (2 файла по несколько байт): проверяется механика
 * «staging -> боевой каталог», а не реальные 29 МиБ. Размеры и хеши настоящей модели
 * зафиксированы в [AsrModelManifest] и проверяются на устройстве при первой установке.
 */
class ModelStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sizes = linkedMapOf("tokens.txt" to 3L, "encoder.onnx" to 4L)

    // sha256("abc") / sha256("defg") — константы, а не файлы: поле инициализируется до
    // того, как JUnit создаст TemporaryFolder, поэтому читать из него здесь нельзя.
    private val hashes = linkedMapOf(
        "tokens.txt" to "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        "encoder.onnx" to "4c8a43980498636e9c1d1595fa5d115af7937c2422dfe68a2520a52b7a5fb4de",
    )

    private fun store(): ModelStore =
        ModelStore(tmp.root, sizes, hashes, modelId = "test-model")

    private fun writeModel(dir: File, tokens: String = "abc", encoder: String = "defg") {
        dir.mkdirs()
        File(dir, "tokens.txt").writeText(tokens)
        File(dir, "encoder.onnx").writeText(encoder)
    }

    @Test
    fun emptyRootIsNotInstalled() {
        val store = store()
        assertFalse(store.isInstalled())
        assertFalse(store.hasAllFiles(store.modelDir))
    }

    @Test
    fun modelWithAllFilesIsInstalled() {
        val store = store()
        writeModel(store.modelDir)
        assertTrue(store.isInstalled())
    }

    @Test
    fun wrongSizeIsNotInstalled() {
        val store = store()
        writeModel(store.modelDir)
        File(store.modelDir, "tokens.txt").writeText("abcdef")
        assertFalse(store.isInstalled())
    }

    @Test
    fun verifyChecksContentNotOnlySizes() {
        val store = store()
        writeModel(store.modelDir)
        assertTrue(store.verify(store.modelDir))

        // Тот же размер, другой контент — быстрая проверка проходит, полная нет.
        File(store.modelDir, "tokens.txt").writeText("xyz")
        assertTrue(store.isInstalled())
        assertFalse(store.verify(store.modelDir))
    }

    @Test
    fun installMovesStagingIntoPlace() {
        val store = store()
        val staging = store.newStagingDir()
        writeModel(staging)

        assertTrue(store.installFrom(staging))
        assertTrue(store.modelDir.isDirectory)
        assertTrue(store.isInstalled())
        assertFalse("staging должен исчезнуть после переименования", staging.exists())
    }

    @Test
    fun installRejectsIncompleteStaging() {
        val store = store()
        val staging = store.newStagingDir()
        staging.mkdirs()
        File(staging, "tokens.txt").writeText("abc")

        assertFalse(store.installFrom(staging))
        assertFalse("полумодель не должна попасть в боевой каталог", store.modelDir.exists())
    }

    @Test
    fun installReplacesPreviousModelWithoutLeftovers() {
        val store = store()
        val first = store.newStagingDir()
        writeModel(first)
        assertTrue(store.installFrom(first))

        val second = store.newStagingDir()
        writeModel(second, tokens = "xyz")
        assertTrue(store.installFrom(second))

        assertEquals("xyz", File(store.modelDir, "tokens.txt").readText())
        val leftovers = store.rootDir.listFiles()!!
            .filter { it.name.startsWith("${store.modelDir.name}.previous") || it.name.startsWith("staging-") }
        assertTrue("временные каталоги должны быть удалены: ${leftovers.map { it.name }}", leftovers.isEmpty())
    }

    @Test
    fun stagingDirsAreUnique() {
        val store = store()
        val a = store.newStagingDir()
        val b = store.newStagingDir()
        assertTrue(a.isDirectory)
        assertTrue(b.isDirectory)
        assertFalse(a.absolutePath == b.absolutePath)
    }

    @Test
    fun deleteModelRemovesDirectory() {
        val store = store()
        writeModel(store.modelDir)
        assertTrue(store.deleteModel())
        assertFalse(store.modelDir.exists())
        // Повторное удаление — не ошибка (идемпотентно).
        assertTrue(store.deleteModel())
    }

    @Test
    fun sha256MatchesKnownValue() {
        val file = File(tmp.root, "abc.bin")
        file.writeText("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ModelStore.sha256(file))
        assertEquals("asr", ModelStore.rootForAppFilesDir(tmp.root).name)
    }
}
