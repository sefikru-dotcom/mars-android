package ru.mars.mobile.tts

/**
 * Паспорт русской офлайн TTS-модели: что качаем, чем проверяем и что обязано появиться
 * на диске.
 *
 * Модель — Piper/VITS `vits-piper-ru_RU-irina-medium` из официального релиза k2-fsa:
 * женский голос, 1 спикер, 22 050 Гц, ~64 MiB архив / ~79 MiB на диске. Это ПОЛНОТОЧНАЯ
 * (float32) версия, а не int8. Раньше стояла `vits-piper-ru_RU-irina-medium-int8`
 * (18,5 МБ на диске): int8-квантование (110 узлов `DynamicQuantizeLinear` в графе) добавляет
 * к голосу шум квантования, и на слух он читается как «роботность». Полная модель шумит
 * меньше (замеры — в отчёте волны 9), ценой ~63 МБ на диске вместо 18,5 МБ.
 *
 * В APK модель не кладём по той же причине, что и ASR-модель: она нужна части сессий,
 * а размер APK удвоила бы всем.
 *
 * ## Чем этот манифест отличается от [ru.mars.mobile.asr.AsrModelManifest]
 * У ASR-модели все файлы плоские. У TTS-модели рядом с `tokens.txt` лежит ДЕРЕВО
 * `espeak-ng-data/` — 355 файлов в 37 каталогах (`lang/<группа>/<язык>`, `voices/!v/<вариант>`,
 * `phontab`, `phondata`, `phonindex`, `intonations`). Дерево нужно целиком: без него
 * espeak-ng не находит правила произношения и русский текст читается по буквам.
 *
 * Поэтому здесь два уровня проверки:
 * - [FILES]/[FILE_SHA256] — плоские файлы корня модели, точные размеры и хеши
 *   (их проверяет `ModelStore.verify`, он умеет только плоский список);
 * - [ESPEAK_FILE_COUNT]/[ESPEAK_DIR_COUNT]/[ESPEAK_BYTES] и [ESPEAK_REQUIRED] — контроль
 *   дерева по количеству и обязательным точкам входа (пофайловых хешей у него нет).
 *
 * Значения посчитаны по реально скачанным архивам, у которых сошёлся [ARCHIVE_SHA256]
 * (полная) и [FALLBACK_ARCHIVE_SHA256] (int8):
 * `ls -l` по корню, `find … -type f | wc -l`, `sha256sum`. Не подставлять «примерные».
 */
object OfflineTtsManifest {
    /**
     * Имя каталога модели: совпадает с каталогом верхнего уровня внутри архива.
     *
     * Без суффикса `-int8`: это float32-версия голоса.
     */
    const val MODEL_ID = "vits-piper-ru_RU-irina-medium"

    /** Официальный релиз k2-fsa (полноточный архив, 67 153 308 байт). */
    const val ARCHIVE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$MODEL_ID.tar.bz2"

    /**
     * SHA-256 полного архива (67 153 308 байт). Проверяется ДО распаковки.
     * Посчитан на скачанном архиве: `sha256sum`, `openssl dgst -sha256`.
     */
    const val ARCHIVE_SHA256 =
        "1fc0f54e5e084fe287c07909f2f6e0ba6d857864cf800e3ab80286a4e8233008"

    const val ARCHIVE_SIZE: Long = 67_153_308L

    // --- запасной путь: int8 -------------------------------------------------------------
    //
    // Оставлен, потому что он ЛЁГКИЙ: 21 МиБ архива и 36,6 МиБ на диске против 64/79 МиБ.
    // Если полную модель скачать не удалось (нет места, обрыв на 67 МБ), хост ставит эту:
    // голос хуже, но синтез работает. Никакой отдельной логики — те же поля манифеста,
    // переданные в [OfflineTtsModel] явно.

    /** Имя каталога int8-модели. Совпадает с каталогом верхнего уровня её архива. */
    const val FALLBACK_MODEL_ID = "$MODEL_ID-int8"

    const val FALLBACK_ARCHIVE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
            "$FALLBACK_MODEL_ID.tar.bz2"

    /** SHA-256 int8-архива (21 149 417 байт) — то же значение, что проверялось в волне 3. */
    const val FALLBACK_ARCHIVE_SHA256 =
        "b0000a509f7551a80742eed5c43b8eb03f469cb9f0a42f6feee96ce0da0ebab8"

    const val FALLBACK_ARCHIVE_SIZE: Long = 21_149_417L

    /**
     * Размер int8-`.onnx` (только для справки и для [FALLBACK_FILES]: на диске у двух
     * вариантов отличаются только сам `.onnx`, остальное совпадает байт в байт).
     */
    const val FALLBACK_MODEL_FILE_BYTES: Long = 18_579_710L

    const val FALLBACK_MODEL_FILE_SHA256 =
        "024e07e0c2d3bacde9ebbb1b0801642662a07fe769b0899471b0a44a62c70bae"

    /** Сам синтезатор (float32 ONNX, 63 149 192 байта). */
    const val MODEL_FILE = "ru_RU-irina-medium.onnx"

    /**
     * Конфиг Piper (sample_rate, espeak voice, inference). sherpa-onnx его не читает —
     * держим как часть архива: по нему видно, каким голосом и с какими параметрами
     * обучена модель. У полной и int8-версий он идентичен (тот же хеш).
     */
    const val MODEL_CONFIG_FILE = "ru_RU-irina-medium.onnx.json"

    const val TOKENS_FILE = "tokens.txt"

    const val MODEL_CARD_FILE = "MODEL_CARD"

    /** Каталог словарей произношения espeak-ng внутри каталога модели (аргумент `dataDir`). */
    const val ESPEAK_DIR = "espeak-ng-data"

    /** Частота модели. AudioTrack строится по фактической (`OfflineTts.sampleRate()`). */
    const val SAMPLE_RATE = 22_050

    /** Один спикер (irina). Голос в `speak` не выбирается. */
    const val NUM_SPEAKERS = 1

    // --- параметры инференса -------------------------------------------------------------
    //
    // Заводские значения из `ru_RU-irina-medium.onnx.json` (`inference.*`): noise_scale 0.667,
    // noise_w 0.8, length_scale 1.0. Вшиты здесь, чтобы менять их без пересборки логики:
    // бэкенд берёт их только отсюда (и не хардкодит в конструкторе движка).

    /**
     * «Дрожь» просодии (заводское 0.667).
     *
     * Волна 20: 0.667 на приёмке давало металлический дребезг на длинных фразах — у VITS он
     * слышен как «звон» поверх голоса. 0.50 сглаживает тембр (речь остаётся живой за счёт
     * [NOISE_SCALE_W] и пауз [ru.mars.mobile.tts.OfflineTtsBackend]), ниже 0.4 начинается
     * «механическая» ровная интонация.
     */
    const val NOISE_SCALE = 0.50f

    /**
     * Разброс длительностей фонем (заводское 0.8).
     *
     * 0.65 вместо 0.8: вместе со сглаженной [NOISE_SCALE] убирает «дрожание» длительностей,
     * из-за которого Ирина то тараторила, то растягивала слоги внутри одного предложения.
     */
    const val NOISE_SCALE_W = 0.65f

    /**
     * Темп: 1.0 — как в модели, >1 медленнее (Piper трактует `length_scale` как растяжение
     * времени). 1.08 замерено: фраза из двух предложений 7,060 с -> 7,422 с (+5,1 %),
     * генерация 1,72 с -> 2,15 с. Воспринимается живее за счёт пауз. Дальше 1.15 не
     * поднимать — появляется «диктор из 90-х», а не разговорная речь.
     */
    const val LENGTH_SCALE = 1.08f

    /** Плоские файлы корня модели и их точные размеры в байтах (полная модель). */
    val FILES: Map<String, Long> = linkedMapOf(
        MODEL_FILE to 63_149_192L,
        MODEL_CONFIG_FILE to 4_765L,
        TOKENS_FILE to 899L,
        MODEL_CARD_FILE to 271L,
    )

    /** SHA-256 плоских файлов: проверка после распаковки (ловит битый/подменённый архив). */
    val FILE_SHA256: Map<String, String> = linkedMapOf(
        MODEL_FILE to "44c31fdb9de753d25380b558effb84f250d98acb2cc0971fea78ec40eda98cdd",
        MODEL_CONFIG_FILE to "c2ec28bb38e2b59e93b959b3e40348c1afebbd272f30fed5d41205d08e98a9d7",
        TOKENS_FILE to "da19d3f9a439574f66c072baafced1d1c79f42d8c5981619ea9274a7ab0dbba5",
        MODEL_CARD_FILE to "f4fc2deb0e8c6219f29202b8dd06c9638c5ffd50ad5c6b2c90cf1aa59d507593",
    )

    /**
     * Плоские файлы int8-модели: отличаются от [FILES] ТОЛЬКО размером `.onnx` — конфиг,
     * tokens и карточка в обоих архивах побайтово одинаковы (проверено по SHA-256).
     */
    val FALLBACK_FILES: Map<String, Long> = linkedMapOf(
        MODEL_FILE to FALLBACK_MODEL_FILE_BYTES,
        MODEL_CONFIG_FILE to 4_765L,
        TOKENS_FILE to 899L,
        MODEL_CARD_FILE to 271L,
    )

    /** SHA-256 плоских файлов int8-модели. */
    val FALLBACK_FILE_SHA256: Map<String, String> = linkedMapOf(
        MODEL_FILE to FALLBACK_MODEL_FILE_SHA256,
        MODEL_CONFIG_FILE to FILE_SHA256.getValue(MODEL_CONFIG_FILE),
        TOKENS_FILE to FILE_SHA256.getValue(TOKENS_FILE),
        MODEL_CARD_FILE to FILE_SHA256.getValue(MODEL_CARD_FILE),
    )

    /** Сколько файлов в дереве `espeak-ng-data/`. Меньше — дерево распаковано не целиком. */
    const val ESPEAK_FILE_COUNT = 355

    /** Сколько каталогов в дереве `espeak-ng-data/` (включая корень). */
    const val ESPEAK_DIR_COUNT = 37

    /** Суммарный размер дерева `espeak-ng-data/` в байтах (одинаков у полной и int8). */
    const val ESPEAK_BYTES: Long = 17_991_651L

    /**
     * Точки входа espeak-ng, без которых синтез не работает. Проверяются поштучно:
     * пофайловых хешей у 355 файлов нет (архив уже проверен целиком по [ARCHIVE_SHA256]).
     */
    val ESPEAK_REQUIRED: List<String> = listOf(
        "phontab",
        "phondata",
        "phondata-manifest",
        "phonindex",
        "intonations",
        "lang",
        "voices",
    )

    /** Всего файлов в архиве: 4 плоских + дерево espeak-ng. */
    const val ARCHIVE_FILE_COUNT = ESPEAK_FILE_COUNT + 4
}
