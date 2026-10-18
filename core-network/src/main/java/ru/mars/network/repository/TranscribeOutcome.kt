package ru.mars.network.repository

/**
 * Явный исход `POST /api/transcribe` — по образцу [UploadOutcome], вместо
 * «молча null».
 *
 * Контракт эндпоинта (fixtures/voice-mvp-contract.md, раздел 1): multipart, поле
 * файла ровно `file`; успех — HTTP 200 и JSON `{"ok": true, "transcript": "<текст>"}`;
 * ошибки — 400 (нет поля file / пустое имя / ошибка провайдера), 413 (файл больше
 * 20 МБ), 500, 503 (распознавание выключено на сервере).
 */
sealed interface TranscribeOutcome {

    /** HTTP 200 и непустой `transcript`. */
    data class Success(val transcript: String) : TranscribeOutcome

    /**
     * Явный отказ: HTTP-статус ([httpCode] задан при не-200), пустой/битый ответ
     * или транспортный сбой ([httpCode] == null). [message] — короткая причина,
     * пригодная для показа пользователю.
     */
    data class Failure(val message: String, val httpCode: Int? = null) : TranscribeOutcome
}
