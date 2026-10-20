package ru.mars.mobile.asr

import java.io.File

/**
 * Тонкая обёртка над нативным распознавателем: ровно те операции, которые нужны движку.
 *
 * Зачем интерфейс: сам sherpa-onnx на JVM не запускается (`System.loadLibrary` в
 * companion-объекте + Android-сборка .so), поэтому логика реплики (endpoint, склейка
 * partial/final, состояние «речь началась») живёт в [ru.mars.mobile.StreamingAsrEngine]
 * и проверяется JVM-тестами с фейковым бэкендом. Реальная реализация — [SherpaAsrBackend].
 *
 * Все методы вызываются с потока чтения микрофона под локом движка: реализация не обязана
 * быть потокобезопасной, но обязана не бросать исключений на «пустых» состояниях.
 */
interface AsrBackend {
    /**
     * Поднять распознаватель по каталогу модели.
     * @return false, если native-слой не смог загрузить модель (битые файлы, нет ABI).
     */
    fun initialize(modelDir: File, config: AsrConfig): Boolean

    /** Начать новую реплику: свежий stream, прежний (если был) освобождается. */
    fun newStream(hotwords: List<String>)

    /** Скормить float32 -1..1, 16 кГц, моно. */
    fun acceptSamples(samples: FloatArray)

    /** Есть ли готовые к декодированию кадры. */
    fun isReady(): Boolean

    /** Декодировать один готовый кусок. */
    fun decode()

    /** Достигнут endpoint (человек замолчал). */
    fun isEndpoint(): Boolean

    /** Текущий текст реплики (может быть пустым). */
    fun result(): String

    /**
     * Больше аудио в этой реплике не будет: дать native-слою доработать хвост.
     * После вызова stream непригоден — следующая реплика начинается с [newStream].
     */
    fun finishStream()

    /** Освободить native-ресурсы. Повторный вызов безопасен. */
    fun release()
}
