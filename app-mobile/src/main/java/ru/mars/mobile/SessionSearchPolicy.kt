package ru.mars.mobile

import ru.mars.network.model.SessionItem

/**
 * Чистая политика серверного поиска диалогов (пункт паритета A2).
 *
 * На экране списка сосуществуют ДВА поиска, и они не заменяют друг друга:
 * - **локальный** ([SessionTreePolicy.tree] по `filterQuery`) — мгновенный отзыв на
 *   ввод по уже загруженному списку, работает офлайн, видит только открытые строки;
 * - **серверный** (`GET /api/sessions/search?q=` через
 *   [ru.mars.network.repository.SessionRepository.searchSessionsGlobal]) — находит
 *   диалоги, которых в локальном списке нет вовсе (другие профили, глубокая история,
 *   совпадение по содержимому сообщений).
 *
 * Здесь живёт вся арифметика второго, чтобы Activity осталась слоем представления:
 * когда запрос вообще имеет смысл отправлять, какие серверные строки ещё НЕ видны
 * на экране и как подписать секцию.
 */
object SessionSearchPolicy {

    /**
     * Минимальная длина запроса для запроса к серверу. Один символ даёт у сервера
     * почти весь список — это дорого и бесполезно, поэтому такой запрос остаётся
     * только локальным фильтром.
     */
    const val MIN_QUERY_CHARS = 2

    /**
     * Стоит ли идти на сервер с этим запросом. Пробелы по краям не считаются:
     * `" а "` — это один символ, а не запрос.
     */
    fun shouldQueryServer(query: String): Boolean = query.trim().length >= MIN_QUERY_CHARS

    /**
     * Серверные совпадения, которых НЕТ на экране.
     *
     * [visibleIds] — id строк, уже отрисованных локальным деревом (совпадение по
     * заголовку/превью либо предок такого совпадения). Такие строки не дублируются:
     * сервер вернёт их же, но карточка уже есть выше. Дедупликация идёт и внутри
     * самого ответа (сервер может повторить строку), порядок ответа сохраняется —
     * релевантность расставляет сервер, а не клиент.
     */
    fun serverOnly(remote: List<SessionItem>, visibleIds: Set<String>): List<SessionItem> {
        val seen = mutableSetOf<String>()
        return remote.filter { it.id.isNotBlank() && it.id !in visibleIds && seen.add(it.id) }
    }

    /** Заголовок секции серверных совпадений: «Найдено на сервере: 3». */
    fun sectionLabel(count: Int): String = "Найдено на сервере: $count"

    /**
     * Подпись «почему строка здесь», если сервер нашёл диалог не по заголовку
     * (совпадение по содержимому сообщений). Пусто — подпись не нужна.
     */
    fun matchNote(session: SessionItem, query: String): String? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val inTitle = session.title?.contains(q, ignoreCase = true) == true
        val inPreview = session.preview?.contains(q, ignoreCase = true) == true
        return if (inTitle || inPreview) null else "совпадение внутри диалога"
    }
}
