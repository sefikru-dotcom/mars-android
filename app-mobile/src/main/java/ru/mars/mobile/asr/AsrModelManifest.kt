package ru.mars.mobile.asr

/**
 * Паспорт модели: что качаем, чем проверяем и какие файлы обязаны появиться на диске.
 *
 * Модель НЕ лежит в APK: `sherpa-onnx-streaming-zipformer-small-ru-vosk-int8` весит
 * 23 МиБ в архиве (29 МиБ распакованной), и в APK она удвоила бы размер ради функции,
 * которой пользуется часть сессий. Поэтому архив тянется по HTTPS при первом запуске
 * в filesDir и проверяется по SHA-256.
 *
 * Почему архив, а не отдельные файлы с HuggingFace: у архива официального релиза k2-fsa
 * ровно один хеш — его и проверяем до распаковки. Пять отдельных файлов потребовали бы
 * пяти независимых проверок и допускали бы «полумодель» (часть файлов новой версии,
 * часть старой). Распаковка bz2 на Android без нативной библиотеки невозможна, поэтому
 * в зависимости добавлен commons-compress (чистая Java, ~1 МБ).
 */
object AsrModelManifest {
    /** Имя каталога модели: совпадает с именем каталога в архиве релиза. */
    const val MODEL_ID = "sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16"

    /** Официальный релиз k2-fsa (тот же архив, что проверялся на сервере). */
    const val ARCHIVE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$MODEL_ID.tar.bz2"

    /** SHA-256 архива релиза (24 110 855 байт). Проверяется ДО распаковки. */
    const val ARCHIVE_SHA256 =
        "6ba68a01ff3c5445aaf2d61e9b97b026f1149dcc9049d11af3f44f55176341d8"

    const val ARCHIVE_SIZE: Long = 24_110_855L

    /** Обязательные файлы и их точные размеры в байтах. */
    val FILES: Map<String, Long> = linkedMapOf(
        "tokens.txt" to 6_388L,
        "encoder.int8.onnx" to 26_214_060L,
        "decoder.onnx" to 2_093_080L,
        "joiner.int8.onnx" to 259_417L,
        "bpe.model" to 246_184L,
    )

    /** SHA-256 каждого файла: проверка после распаковки (ловит битый/подменённый архив). */
    val FILE_SHA256: Map<String, String> = linkedMapOf(
        "tokens.txt" to "93bbbc0bae6b78c0bbb743d4aa9fded3bb5ff3aac5f0200e3a769a5a05e0fdf6",
        "encoder.int8.onnx" to "e0db705e94ec35d803b1df4f40cda23d064e1142977c80ab288430b109777a9d",
        "decoder.onnx" to "89b3088a9e20e1ef7f2e85ce1a3478afe6a9c4ac57369cabcc4beb8e95328ea0",
        "joiner.int8.onnx" to "b55784b071ab7512eab4c7c44e4f5478284ef33c83562cc6a249b972515a31e5",
        "bpe.model" to "c7a756aeb3550417d6b2ed3efde9a7aa3eea54787d4eac011e9cce6090c9c64a",
    )
}
