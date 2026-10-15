package ru.mars.mobile

/**
 * Дебаунс серверной записи черновика composer (A15).
 *
 * Контракт записи: `POST /api/session/draft` с телом `{"session_id","text"}`.
 * Слать запрос на каждое нажатие клавиши нельзя, поэтому политика держит окно
 * [quietMillis] между двумя записями одного диалога:
 *
 * - [delayFor] отвечает, когда отправлять: `null` — сохранять нечего (текст тот же,
 *   что уже ушёл на сервер), `0` — можно прямо сейчас, `>0` — через столько мс
 *   (окно после прошлой записи ещё не истекло);
 * - [onSaved] фиксирует факт записи (вызывается и при неуспехе: окно закрыто,
 *   иначе офлайн-сессия забила бы очередь запросами);
 * - [drainOnPause] — уход с экрана: остаток досылается ВНЕ окна, но только если
 *   текст действительно отличается от последнего записанного;
 * - [onSessionChanged] — смена диалога: у нового диалога своё окно и свой
 *   «последний записанный» текст (иначе чужой черновик не записался бы).
 *
 * Состояние — только текст и метка времени; никакого Android и никаких корутин,
 * поэтому дебаунс полностью покрывается JVM unit-тестами.
 */
class ServerDraftSyncPolicy(
    private val quietMillis: Long = DEFAULT_QUIET_MILLIS,
) {

    private var lastSavedAt: Long? = null
    private var lastSavedText: String? = null

    /** Смена диалога: окно и «последний записанный текст» относятся к старому. */
    fun onSessionChanged() {
        lastSavedAt = null
        lastSavedText = null
    }

    /**
     * Когда отправлять текущий текст [text] в момент [now]:
     * `null` — нечего, `0` — сейчас, `>0` — задержка в миллисекундах.
     */
    fun delayFor(text: String, now: Long): Long? {
        if (text == lastSavedText) return null
        val at = lastSavedAt ?: return 0L
        val elapsed = now - at
        return if (elapsed >= quietMillis) 0L else quietMillis - elapsed
    }

    /** Запись выполнена (успешно или нет) — окно начинается заново. */
    fun onSaved(text: String, now: Long) {
        lastSavedText = text
        lastSavedAt = now
    }

    /**
     * Уход с экрана: `true`, если остаток черновика надо досылать немедленно,
     * игнорируя окно. Повторная досылка того же текста не нужна.
     */
    fun drainOnPause(text: String): Boolean = text != lastSavedText

    companion object {
        /**
         * Окно покоя между записями: ~2 с. Быстрее — поток запросов на каждый
         * набранный символ, медленнее — потерянный при падении процесс хвост.
         */
        const val DEFAULT_QUIET_MILLIS: Long = 2_000
    }
}

/**
 * Приоритет источника текста композитора при открытии диалога (A15).
 *
 * Порядок строгий: уже набранный пользователем текст > локальный черновик >
 * серверный черновик. Серверный черновик только ДОПОЛНЯЕТ локальный (устройство
 * могло быть офлайн и хранит более свежую правку) и никогда не перетирает то,
 * что пользователь печатает прямо сейчас. `null` — «поле не трогать».
 */
object ServerDraftAdoption {

    fun textFor(serverDraft: String?, localDraft: String?, composerText: String): String? {
        if (composerText.isNotBlank()) return null
        localDraft?.takeIf { it.isNotBlank() }?.let { return it }
        return serverDraft?.takeIf { it.isNotBlank() }
    }
}
