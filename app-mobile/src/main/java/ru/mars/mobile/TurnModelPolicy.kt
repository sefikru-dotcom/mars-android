package ru.mars.mobile

/**
 * Подпись фактической модели хода в карточке ответа (волна C, фича A) — чистая
 * JVM-политика, без Android.
 *
 * Источник истины — сервер, не локальный пикер:
 * - основной: аддитивные ключи `model`/`model_provider` самого раннего SSE-кадра
 *   `context_status` ([ru.mars.network.model.ContextStatusUpdate.model]) — это ровно
 *   те resolved_model/resolved_provider, с которыми конструируется агент;
 * - фоллбэк: `effective_model`/`effective_model_provider` из HTTP-ответа
 *   `POST /api/chat/start` ([ru.mars.network.model.ChatEvent.StreamStarted]) — они
 *   присутствуют ТОЛЬКО если модель была нормализована сервером, поэтому на HTTP
 *   нельзя закладываться как на единственный источник.
 *
 * Локально выбранная модель (пикер) в политику не входит: её хост показывает
 * только до прихода серверного значения и никогда — после. Пустое/пробельное
 * значение трактуется как «сервер не сообщил» (null), догадки не подставляются.
 *
 * Форма подписи — CLI-parity `provider/model`: транспортный префикс провайдера
 * `custom:` для чтения снимается, дублирование провайдера в самом идентификаторе
 * модели (`openrouter/openrouter/...`, `openai` + `openai/gpt-5`) не удваивается.
 */
object TurnModelPolicy {

    /** Транспортный префикс именованных custom-провайдеров (Session.compact parity). */
    private const val CUSTOM_PROVIDER_PREFIX = "custom:"

    /**
     * Подпись хода: `model` или `provider/model`; null, если модель неизвестна.
     * Провайдер без модели подписи не даёт: показывать нечего, догадка запрещена.
     */
    fun label(model: String?, provider: String?): String? {
        val name = model?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val vendor = provider?.trim()
            ?.removePrefix(CUSTOM_PROVIDER_PREFIX)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return name
        if (name.equals(vendor, ignoreCase = true)) return name
        if (name.startsWith("$vendor/", ignoreCase = true)) return name
        return "$vendor/$name"
    }
}
