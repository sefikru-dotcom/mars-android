package ru.mars.mobile.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Паспорт модели: одна модель синтеза, и её бюджет места посчитан от реальных размеров.
 *
 * Тесты-инварианты, а не пересказ констант: они падают, когда кто-то меняет размер `.onnx`
 * или дерево espeak и забывает, что от этих чисел зависит проверка свободного места.
 */
class OfflineTtsManifestTest {

    private val mib = 1024L * 1024L

    @Test
    fun `устанавливается ровно одна модель — полноточная Ирина`() {
        assertEquals("vits-piper-ru_RU-irina-medium", OfflineTtsManifest.MODEL_ID)
        assertFalse("int8 в имени модели недопустим", OfflineTtsManifest.MODEL_ID.contains("int8"))
        assertTrue(
            "качается архив именно этой модели: ${OfflineTtsManifest.ARCHIVE_URL}",
            OfflineTtsManifest.ARCHIVE_URL.endsWith("${OfflineTtsManifest.MODEL_ID}.tar.bz2"),
        )
    }

    @Test
    fun `устаревший каталог опознаётся по другому имени и другому onnx`() {
        assertNotEquals(OfflineTtsManifest.MODEL_ID, OfflineTtsManifest.LEGACY_MODEL_ID)
        assertTrue(OfflineTtsManifest.LEGACY_MODEL_ID.endsWith("-int8"))
        assertNotEquals(
            "int8 и полная модель обязаны различаться размером .onnx",
            OfflineTtsManifest.FILES.getValue(OfflineTtsManifest.MODEL_FILE),
            OfflineTtsManifest.LEGACY_MODEL_FILE_BYTES,
        )
        assertEquals(
            "паспорт устаревшей модели сверяет тот же файл, что и установка",
            OfflineTtsManifest.MODEL_FILE,
            LegacyModelSpec.fromManifest().fileName,
        )
    }

    @Test
    fun `распакованный объём считается из размеров файлов`() {
        val expected = OfflineTtsManifest.FILES.values.sum() + OfflineTtsManifest.ESPEAK_BYTES
        assertEquals(expected, OfflineTtsManifest.UNPACKED_BYTES)
        // 60,2 МиБ плоских файлов + 17,2 МиБ дерева espeak-ng.
        assertEquals(81_146_778L, OfflineTtsManifest.UNPACKED_BYTES)
    }

    @Test
    fun `пик установки — архив плюс распакованное дерево плюс запас`() {
        assertEquals(
            OfflineTtsManifest.ARCHIVE_SIZE +
                OfflineTtsManifest.UNPACKED_BYTES +
                OfflineTtsManifest.INSTALL_SLACK_BYTES,
            OfflineTtsManifest.PEAK_BYTES,
        )
        // Регрессия: порог когда-то равнялся размеру архива, и установка начиналась там,
        // где распаковка заведомо не влезала.
        assertTrue(
            "пик обязан быть заметно больше архива: архив ${OfflineTtsManifest.ARCHIVE_SIZE}, " +
                "пик ${OfflineTtsManifest.PEAK_BYTES}",
            OfflineTtsManifest.PEAK_BYTES > OfflineTtsManifest.ARCHIVE_SIZE + OfflineTtsManifest.UNPACKED_BYTES,
        )
        // ≈149 МиБ: столько и должно называться человеку в причине отказа.
        assertEquals(149L, OfflineTtsManifest.PEAK_BYTES / mib)
    }

    @Test
    fun `плоские файлы описаны размерами и хешами одновременно`() {
        assertEquals(
            "на каждый файл обязан быть и размер, и SHA-256",
            OfflineTtsManifest.FILES.keys,
            OfflineTtsManifest.FILE_SHA256.keys,
        )
        assertEquals(64, OfflineTtsManifest.FILE_SHA256.getValue(OfflineTtsManifest.MODEL_FILE).length)
    }

    @Test
    fun `счётчик файлов архива не расходится с описанием`() {
        // `unpack` сверяет число извлечённых файлов с этим значением: разойдётся — установка
        // будет отказывать на исправном архиве.
        assertEquals(
            OfflineTtsManifest.ESPEAK_FILE_COUNT + OfflineTtsManifest.FILES.size,
            OfflineTtsManifest.ARCHIVE_FILE_COUNT,
        )
    }
}
