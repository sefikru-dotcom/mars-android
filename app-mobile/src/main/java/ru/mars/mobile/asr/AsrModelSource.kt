package ru.mars.mobile.asr

import java.io.File

/**
 * Источник файлов модели для [ru.mars.mobile.StreamingAsrEngine].
 *
 * Разделяет «где лежит модель» и «как её достали»: на устройстве это [ModelDownloader]
 * (HTTPS + распаковка), в JVM-тестах — фейк с временным каталогом, поэтому движок
 * тестируется без Android и без сети.
 */
interface AsrModelSource {
    /** Каталог, в котором лежат tokens.txt / encoder / decoder / joiner / bpe.model. */
    val modelDir: File

    /** Модель уже на диске и нужного размера (быстрая проверка, без хеширования). */
    fun isAvailable(): Boolean

    /**
     * Доготовить модель: скачать и распаковать, если её нет.
     * @return true, если после вызова модель на диске и пригодна.
     */
    fun ensure(): Boolean
}
