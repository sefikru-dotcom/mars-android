package ru.mars.mobile.tts

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.mars.mobile.asr.ModelStore

/**
 * Безопасность удаления устаревшей int8-модели и проверка свободного места при установке.
 *
 * ## Почему паспорт подменяется
 * В приложении ровно один офлайн-голос — полноточная Ирина ([OfflineTtsManifest.MODEL_ID]).
 * Каталог `…-int8` от прежних версий занимает 36,6 МиБ и не читается ни одним вызывающим,
 * поэтому его надо снять. Но «снять» не значит «почистить `filesDir/tts`»: рядом лежат
 * архив, staging и пользовательские файлы приложения. Удаление разрешено ровно для одного
 * каталога, опознанного по имени + размеру + SHA-256 `.onnx`.
 *
 * Отсюда тесты ниже: удаляется только точное совпадение, всё сомнительное остаётся.
 * Паспорт — [LegacyModelSpec] на 4 байта вместо настоящего 18,5-мегабайтного файла:
 * проверяется механика опознания, а не объём.
 */
class OfflineTtsModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val logs = mutableListOf<String>()

    /** Каталог TTS-хранилища (`filesDir/tts` в приложении). */
    private val root: File get() = File(tmp.root, "tts")

    private fun store(): ModelStore = ModelStore(rootDir = root, modelId = CURRENT_MODEL_ID)

    /**
     * Паспорт «старой int8-модели» на 4 байта (`defg`).
     * `sha256("defg")` — константа, чтобы паспорт не зависел от файловой системы.
     */
    private fun spec(id: String = LEGACY_ID): LegacyModelSpec = LegacyModelSpec(
        id = id,
        fileName = OfflineTtsManifest.MODEL_FILE,
        size = 4L,
        sha256 = SHA_DEFG,
    )

    private fun model(
        store: ModelStore = store(),
        freeBytes: () -> Long = { -1L },
        legacy: LegacyModelSpec = spec(),
        archiveUrl: String = UNREACHABLE_URL,
    ): OfflineTtsModel = OfflineTtsModel(
        store = store,
        archiveUrl = archiveUrl,
        log = { logs += it },
        freeBytes = freeBytes,
        legacy = legacy,
    )

    private fun writeLegacyDir(content: String = "defg", id: String = LEGACY_ID): File {
        val dir = File(root, id)
        dir.mkdirs()
        File(dir, OfflineTtsManifest.MODEL_FILE).writeText(content)
        File(dir, OfflineTtsManifest.TOKENS_FILE).writeText("abc")
        return dir
    }

    // --- удаление устаревшей модели ---------------------------------------------------------

    @Test
    fun `опознанный int8-каталог удаляется целиком`() {
        val legacyDir = writeLegacyDir()
        assertTrue("каталог создан для теста", legacyDir.isDirectory)

        assertTrue(model().purgeLegacyModel())
        assertFalse("каталог int8-модели должен исчезнуть", legacyDir.exists())
    }

    @Test
    fun `каталог с тем же именем но недокачанным onnx остаётся`() {
        // Обрыв на 3 байтах из 4: это не модель, а мусор. Удалять «по имени» нельзя.
        val legacyDir = writeLegacyDir(content = "def")

        assertFalse(model().purgeLegacyModel())
        assertTrue("недокачанный каталог не трогаем", legacyDir.exists())
        assertTrue(
            "отказ обязан быть в логе",
            logs.any { it.contains("is not the known int8 model") },
        )
    }

    @Test
    fun `каталог с тем же именем и размером но другим содержимым остаётся`() {
        // Ровно тот случай, ради которого сверяется SHA-256: размер совпал, файл — чужой.
        val legacyDir = writeLegacyDir(content = "abcd")

        assertFalse(model().purgeLegacyModel())
        assertTrue(legacyDir.exists())
        assertTrue(File(legacyDir, OfflineTtsManifest.MODEL_FILE).isFile)
    }

    @Test
    fun `каталог текущей модели не удаляется даже если паспорт указывает на него`() {
        // Защита от ошибки в паспорте: имя текущей модели под удаление не попадает никогда.
        val current = File(root, CURRENT_MODEL_ID)
        current.mkdirs()
        File(current, OfflineTtsManifest.MODEL_FILE).writeText("defg")

        assertFalse(model(legacy = spec(id = CURRENT_MODEL_ID)).purgeLegacyModel())
        assertTrue("текущая модель обязана остаться", current.isDirectory)
    }

    @Test
    fun `паспорт с выходом из корня не удаляет ничего снаружи`() {
        // `..` в имени — попытка выйти за пределы TTS-хранилища (там пользовательские данные).
        val outside = File(tmp.root, "evil")
        outside.mkdirs()
        File(outside, OfflineTtsManifest.MODEL_FILE).writeText("defg")

        assertFalse(model(legacy = spec(id = "../evil")).purgeLegacyModel())
        assertTrue("каталог вне корня не трогаем", outside.isDirectory)
    }

    @Test
    fun `симлинк на каталог вне корня не разыменовывается`() {
        // Канонический путь обязан лежать прямо в корне: ссылка на чужой каталог отсекается,
        // иначе `deleteRecursively` снёс бы содержимое по ссылке.
        val outside = File(tmp.root, "outside")
        outside.mkdirs()
        File(outside, OfflineTtsManifest.MODEL_FILE).writeText("defg")
        root.mkdirs()
        val link = File(root, LEGACY_ID).toPath()
        try {
            Files.createSymbolicLink(link, outside.toPath())
        } catch (denied: Exception) {
            // Windows без SeCreateSymbolicLinkPrivilege: проверять нечего, но не падать.
            Assume.assumeNoException("симлинки недоступны на этой машине", denied)
        }

        assertFalse(model().purgeLegacyModel())
        assertTrue(
            "цель ссылки обязана остаться",
            File(outside, OfflineTtsManifest.MODEL_FILE).isFile,
        )
    }

    @Test
    fun `повторное удаление безопасно`() {
        assertTrue("удалять нечего — это успех", model().purgeLegacyModel())
    }

    // --- свободное место --------------------------------------------------------------------

    @Test
    fun `при нехватке места установка не начинается и архив не создаётся`() {
        val sut = model(freeBytes = { 1L })

        assertFalse(sut.ensure())
        assertTrue(
            "причина обязана быть в логе",
            logs.any { it.contains("not enough free space") },
        )
        assertEquals(
            "ни одного .part: до сети дело не дошло",
            0,
            root.listFiles()?.count { it.name.endsWith(".part") } ?: 0,
        )
    }

    @Test
    fun `порог места — пик установки а не размер архива`() {
        // Регрессия: раньше порог был равен размеру архива (64 МиБ), и телефон с 70 МиБ
        // свободных качал 64 МиБ, чтобы упасть на распаковке 77 МиБ дерева.
        val justBelow = OfflineTtsManifest.PEAK_BYTES - 1
        val sut = model(freeBytes = { justBelow })

        assertFalse(sut.ensure())
        assertTrue(logs.any { it.contains("not enough free space") })
    }

    @Test
    fun `ровно пикового объёма хватает`() {
        // Проверяется граница: ровно PEAK_BYTES — уже не отказ (иначе «чуть-чуть не хватило»
        // навсегда блокировало бы установку на устройстве с точным запасом).
        val sut = model(freeBytes = { OfflineTtsManifest.PEAK_BYTES })

        sut.ensure()
        assertFalse(
            "места достаточно — отказ по месту недопустим",
            logs.any { it.contains("not enough free space") },
        )
    }

    @Test
    fun `неизвестный размер свободного места установку не блокирует`() {
        // `-1` = «неизвестно» (StatFs не ответил, JVM-заглушка). Обвинять место без данных
        // нельзя: иначе на устройстве с исправной ФС установка не началась бы никогда.
        val sut = model(freeBytes = { -1L })

        sut.ensure()
        assertFalse(
            "неизвестный размер — не повод отказывать",
            logs.any { it.contains("not enough free space") },
        )
    }

    @Test
    fun `устаревший каталог снимается до проверки места`() {
        // Порядок важен: 36,6 МиБ int8-модели освобождаются ДО решения «хватает ли места».
        val legacyDir = writeLegacyDir()
        val sut = model(freeBytes = { 1L })

        assertFalse(sut.ensure())
        assertFalse("место освобождается первым делом", legacyDir.exists())
    }

    @Test
    fun `устаревший каталог снимается даже когда полная модель уже стоит`() {
        // Главный случай для живых устройств: полную Ирину поставили, а int8-каталог остался.
        // `ensure()` выходит по `isInstalled()` — значит, чистка обязана быть ДО этой проверки.
        val legacyDir = writeLegacyDir()
        val sut = model(store = installedStore())

        assertTrue("модель считается установленной", sut.isInstalled())
        assertTrue(sut.ensure())
        assertFalse("мёртвый int8-каталог обязан исчезнуть", legacyDir.exists())
    }

    /**
     * Хранилище с «уже установленной» моделью: вместо 63 МБ `.onnx` — файл на 1 байт, вместо
     * 355 файлов espeak — столько же пустых. Проверяется порядок вызовов, а не объём.
     */
    private fun installedStore(): ModelStore {
        val store = ModelStore(
            rootDir = root,
            expectedFiles = mapOf(MARKER_FILE to 1L),
            fileSha256 = mapOf(MARKER_FILE to SHA_A),
            modelId = CURRENT_MODEL_ID,
        )
        val dir = store.modelDir
        dir.mkdirs()
        File(dir, MARKER_FILE).writeText("a")
        val espeak = File(dir, OfflineTtsManifest.ESPEAK_DIR)
        espeak.mkdirs()
        OfflineTtsManifest.ESPEAK_REQUIRED.forEach { File(espeak, it).writeText("") }
        repeat(OfflineTtsManifest.ESPEAK_FILE_COUNT) { File(espeak, "f$it").writeText("") }
        return store
    }

    private companion object {
        const val CURRENT_MODEL_ID = "vits-piper-ru_RU-irina-medium"
        const val LEGACY_ID = "vits-piper-ru_RU-irina-medium-int8"

        /** Порт 9 на loopback закрыт: соединение рвётся сразу, реальная сеть не нужна. */
        const val UNREACHABLE_URL = "http://127.0.0.1:9/never.tar.bz2"

        /** `sha256("defg")`. */
        const val SHA_DEFG = "4c8a43980498636e9c1d1595fa5d115af7937c2422dfe68a2520a52b7a5fb4de"

        /** Маркерный файл «установленной» модели и `sha256("a")`. */
        const val MARKER_FILE = "a.txt"
        const val SHA_A = "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb"
    }
}
