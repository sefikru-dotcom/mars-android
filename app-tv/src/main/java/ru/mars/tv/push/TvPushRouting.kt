package ru.mars.tv.push

/**
 * Куда ведёт тап по push-уведомлению (чистая политика, без Android — JVM-тесты).
 *
 * Разрыв, который она закрывает: уведомление «требуется подтверждение» открывало
 * [ru.mars.tv.TvChatActivity] БЕЗ привязки к диалогу — экран показывал последний
 * локально привязанный чат (или пустой), и ход, ждущий решения, оставался невидимым
 * с пульта. Теперь [TvPushRouting.resolve] вытаскивает сессию и вид события из
 * extras интента (их кладёт [TvPushNotifications]) либо из deep link (`url`
 * data-payload'а), а [TvChatActivity] открывает ИМЕННО этот диалог.
 *
 * Контракт wire-ключей — тот же, что у core `PushMessagePolicy`
 * (`session_id`/`kind`, см. её `KEY_*`): серверная половина push'а не меняется,
 * ТВ читает те же data-поля, что и мобильный клиент.
 */
data class TvPushRoute(
    /** Диалог, который надо открыть; null — уведомление не про диалог. */
    val sessionId: String? = null,
    /** Вид события (`approval`/`clarify`/`chat`/…), дословно с сервера; null — не сообщён. */
    val kind: String? = null,
) {
    val hasSession: Boolean get() = !sessionId.isNullOrBlank()

    /** Ход ждёт РЕШЕНИЯ пользователя — на ТВ это повод открыть диалог и подсветить состояние. */
    val isDecision: Boolean get() = kind == TvPushRouting.KIND_APPROVAL || kind == TvPushRouting.KIND_CLARIFY

    companion object {
        /** Ничего не маршрутизируется (уведомление без сессии). */
        val NONE = TvPushRoute()
    }
}

object TvPushRouting {

    /** Extra интента: id диалога из data-payload (`session_id`). */
    const val EXTRA_SESSION_ID = "ru.mars.tv.push.session_id"

    /** Extra интента: вид события из data-payload (`kind`). */
    const val EXTRA_KIND = "ru.mars.tv.push.kind"

    const val KIND_APPROVAL = "approval"
    const val KIND_CLARIFY = "clarify"

    /** Ключи data-payload FCM — те же, что у core `PushMessagePolicy`. */
    private const val WIRE_SESSION_ID = "session_id"
    private const val WIRE_KIND = "kind"

    /** Параметры deep link, если сервер прислал `url` вместо/вместе с data-полями. */
    private const val LINK_SESSION_ID = "session_id"
    private const val LINK_SESSION = "session"
    private const val LINK_KIND = "kind"

    /**
     * Маршрут прямо из data-payload FCM (путь [TvPushMessagingService]): сессия и вид
     * события читаются из тех же ключей, что разбирает `PushMessagePolicy`.
     */
    fun fromData(data: Map<String, String>): TvPushRoute = TvPushRoute(
        sessionId = clean(data[WIRE_SESSION_ID]),
        kind = normalizeKind(data[WIRE_KIND]),
    )

    /**
     * Маршрут из интента экрана. Приоритет — у явных extras (их положил клиент при
     * показе уведомления), deep link — запасной канал для уведомлений, пришедших
     * только с `url`. Пустые/битые значения не превращаются в догадки: null.
     */
    fun resolve(extraSessionId: String?, extraKind: String?, dataUri: String?): TvPushRoute {
        val link = parseDeepLink(dataUri)
        return TvPushRoute(
            sessionId = clean(extraSessionId) ?: link.sessionId,
            kind = normalizeKind(extraKind) ?: link.kind,
        )
    }

    /**
     * Разбор deep link вида `mars://session/<id>?kind=approval`,
     * `mars://chat?session_id=<id>` или `https://marscore.ru/chat/<id>`.
     * Идентификатор берётся из query (`session_id`/`session`), иначе — последний
     * непустой сегмент пути. Всё, что не разобралось, — null (без исключений:
     * кривой url из уведомления не должен ронять экран).
     */
    fun parseDeepLink(uri: String?): TvPushRoute {
        val raw = clean(uri) ?: return TvPushRoute.NONE
        val parsed = runCatching { java.net.URI(raw) }.getOrNull() ?: return TvPushRoute.NONE
        val query = queryParams(parsed.rawQuery)
        val fromQuery = clean(query[LINK_SESSION_ID]) ?: clean(query[LINK_SESSION])
        val fromPath = parsed.path
            ?.split('/')
            ?.lastOrNull { it.isNotBlank() }
            ?.let(::clean)
        return TvPushRoute(
            sessionId = fromQuery ?: fromPath,
            kind = normalizeKind(query[LINK_KIND]),
        )
    }

    /** `a=1&b=2` -> карта; пустые пары и битые проценты просто пропускаются. */
    private fun queryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=', "").trim()
            if (key.isEmpty()) return@mapNotNull null
            val value = pair.substringAfter('=', "")
            key to runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        }.toMap()
    }

    /** Вид события: нижний регистр, пустое -> null. */
    fun normalizeKind(raw: String?): String? = clean(raw)?.lowercase()

    private fun clean(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }
}
