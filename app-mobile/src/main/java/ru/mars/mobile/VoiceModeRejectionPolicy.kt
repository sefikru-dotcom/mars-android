package ru.mars.mobile

import ru.mars.network.model.ChatEvent

/**
 * Отказ сервера в голосовом ходе: `409 unsupported_voice_mode_backend`.
 *
 * Контракт (`SERVER-CONTRACT.md` §1) различает бэкенды: `legacy-direct`/`legacy-journal`
 * применяют `voice_mode: true` и возвращают эхо в ответе старта, а `runtime-runner`
 * отказывает ДО создания сессии:
 *
 * ```
 * HTTP 409 {"code": "unsupported_voice_mode_backend"}
 * ```
 *
 * Почему это отдельная политика, а не «ещё одна ошибка»:
 * 1. **Повторять бессмысленно.** Отказ выдаётся до материализации сессии, состояние не
 *    создано; второй POST с тем же телом получит тот же 409, а с `voice_mode: false` —
 *    обычный ответ. Второй вариант и есть тихая деградация: человек думает, что говорит с
 *    голосовым режимом, а сервер его не применил.
 * 2. **Ход не должен попасть в офлайн-очередь.** Очередь повторила бы его позже, когда
 *    голосовой цикл уже выключен, и `voice_mode` стал бы `false` — то же самое скрытое
 *    понижение, только отложенное.
 * 3. **Человеку нужно сказать правду.** Не «ошибка сети, повторите», а «сервер не
 *    поддерживает голосовой режим»: это не сбой связи и не его вина.
 *
 * Решение здесь — чистое: строка ошибки на входе, классификация на выходе. Эффекты
 * (статус, транскрипт, отказ от повтора) делает хост.
 */
object VoiceModeRejectionPolicy {

    /** Код отказа из тела ответа сервера. */
    const val CODE = "unsupported_voice_mode_backend"

    /** Что видит человек: причина и что делать дальше, без намёка на «повторите». */
    const val NOTICE =
        "Сервер не поддерживает голосовой режим — этот ход не отправлен. " +
            "Ответьте текстом: ваш текст сохранён (409 unsupported_voice_mode_backend)."

    /** Короткий статус в шапке диалога. */
    const val STATUS = "Голосовой режим недоступен на этом сервере — отправьте текстом"

    /**
     * Отказ ли это голосового режима.
     *
     * Строка приходит из `describeHttpError` как `"HTTP 409: unsupported_voice_mode_backend"`.
     * Требуем и код 409, и маркер причины: один маркер без кода мог бы прийти из текста
     * ответа модели, а один 409 — это и «поток уже занят», и «stale clarify», которые
     * к голосовому режиму отношения не имеют.
     */
    fun isVoiceModeRejected(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val normalized = message.lowercase()
        return "http 409" in normalized && CODE in normalized
    }

    /**
     * Отказ ли это голосового режима — по СТРУКТУРИРОВАННОМУ коду события.
     *
     * Основной путь: `ChatRepository` кладёт `code` из тела ответа в [ChatEvent.Error.errorCode],
     * и решение не зависит от того, как сервер сформулировал `error` (и сформулировал ли вообще).
     * Строковая проверка [isVoiceModeRejected] остаётся фолбэком для тел без `code` (старый
     * контракт: текст ошибки — сам код) и для источников, которые ещё не несут поле.
     */
    fun isVoiceModeRejected(error: ChatEvent.Error): Boolean {
        val code = error.errorCode?.trim()
        if (!code.isNullOrEmpty()) return code.equals(CODE, ignoreCase = true)
        return isVoiceModeRejected(error.message)
    }

    /**
     * Можно ли повторять ход после такого отказа. Всегда `false`: повтор с `voice_mode`
     * даст тот же 409, повтор без него — тихую подмену режима.
     */
    fun isRetryable(message: String?): Boolean = !isVoiceModeRejected(message)

    /** Можно ли повторять ход после отказа, распознанного по структурированному коду. */
    fun isRetryable(error: ChatEvent.Error): Boolean = !isVoiceModeRejected(error)
}
