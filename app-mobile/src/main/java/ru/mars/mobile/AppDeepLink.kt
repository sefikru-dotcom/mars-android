package ru.mars.mobile

/**
 * Deep-link в модель навигации. Парсинг чисто строковый (без android.net.Uri),
 * поэтому политика тестируется на JVM. Поддерживаются формы:
 *
 * - `mars://<раздел>` — корень раздела (`mars://kanban`, `mars://chat`);
 * - `mars://chat/session/<id>` — диалог внутри чата (всегда ≤ 2 перехода:
 *   раздел CHAT + [NavTransition.OpenSession]);
 * - путь без схемы (`/tasks`, `/chat/session/42`) — для intents-фильтров.
 *
 * Неизвестный раздел, пустой/содержащий `/` идентификатор сессии и лишние
 * сегменты дают null — вызывающий слой решает, игнорировать ссылку или
 * открыть стартовое состояние.
 */
sealed interface AppDeepLink {
    /** Корень раздела левого бара. */
    data class Section(val destination: AppDestination) : AppDeepLink

    /** Конкретный диалог внутри чата. */
    data class ChatSession(val sessionId: String) : AppDeepLink

    /** Целевое состояние навигации для этой ссылки. */
    fun toNavState(): AppNavState = when (this) {
        is Section -> AppNavState(destination)
        is ChatSession -> AppNavState(AppDestination.CHAT, ChatLevel.Session(sessionId))
    }

    companion object {
        const val SCHEME = "mars"

        /** Разбор ссылки; null для нераспознанного формата. */
        fun parse(raw: String): AppDeepLink? {
            val path = stripScheme(raw.trim())
            val segments = path.split('/').filter { it.isNotEmpty() }
            return when {
                segments.isEmpty() -> null
                segments.size == 1 -> {
                    val destination = AppDestination.fromKey(segments[0]) ?: return null
                    Section(destination)
                }
                segments.size == 3 &&
                    segments[0] == AppDestination.CHAT.key &&
                    segments[1] == "session" -> {
                    val sessionId = segments[2]
                    if (sessionId.isBlank() || sessionId.contains('/')) null else ChatSession(sessionId)
                }
                else -> null
            }
        }

        private fun stripScheme(raw: String): String {
            val schemePrefix = "$SCHEME://"
            val withoutScheme = if (raw.startsWith(schemePrefix)) {
                raw.removePrefix(schemePrefix)
            } else {
                raw
            }
            // Отбрасываем query/fragment и хост-подобный префикс пустого пути.
            return withoutScheme.substringBefore('?').substringBefore('#').trim('/')
        }
    }
}
