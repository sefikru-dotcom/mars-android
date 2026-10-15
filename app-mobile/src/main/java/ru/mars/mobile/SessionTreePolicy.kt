package ru.mars.mobile

import ru.mars.network.model.SessionItem

/**
 * Узел дерева сессий шторки. Чистая JVM-модель отображения (без Android):
 * [session] — исходная строка списка (`GET /api/sessions`), [depth] — уровень
 * вложенности (0 = верхний уровень), [children] — дочерние сессии
 * (`parent_session_id`), отсортированные по свежести. [expanded] — раскрыт ли
 * узел (смысл имеет только при [hasChildren]); дочерние сессии по умолчанию
 * вложены и НЕ являются отдельными верхнеуровневыми чатами: их видно только
 * когда родитель раскрыт. [isCurrent] — открытая сейчас сессия.
 */
data class SessionTreeNode(
    val session: SessionItem,
    val depth: Int,
    val children: List<SessionTreeNode> = emptyList(),
    val expanded: Boolean = false,
    val isCurrent: Boolean = false,
) {
    /** Есть ли вложенные сессии (маркер «раскрытие по карточке родителя»). */
    val hasChildren: Boolean get() = children.isNotEmpty()
}

/**
 * Чистая политика дерева родительских/дочерних сессий (паритет с WebUI
 * #5244/#5305): принимает плоский список [SessionItem] с полями
 * `parent_session_id` / `source_tag` / `read_only` и строит дерево вместо
 * плоской проекции `ChatUiState.drawerSessions`.
 *
 * Контракт:
 * - **Root grouping.** Корни — сессии без родителя; сортировка по свежести
 *   (`updatedAt ?: createdAt`, строковым сравнением — как в drawerSessions).
 *   Дочерние сессии (включая субагентов) вкладываются в родителя и никогда
 *   не promoted в верхний уровень; отдельный фильтр «субагенты» не нужен —
 *   их скрывает свёрнутый родитель.
 * - **Expand/collapse.** Раскрытие задаётся множеством `expandedParents`
 *   (id родительских сессий); строка видна, когда раскрыты ВСЕ её предки
 *   ([visibleRows]).
 * - **Orphan handling.** Субагент, чей родитель вне списка (parent out of
 *   scope), подавляется — паритет с WebUI; обычный дочерний сирота остаётся
 *   видимым в хвосте корней (свежие сначала). Члены циклов `parent_session_id`
 *   не имеют естественного корня и показываются ровно один раз.
 * - **Search.** [filterForSearch] применяется ДО построения дерева: совпадения
 *   (title/preview, регистронезависимо) плюс цепочка предков как контекст —
 *   иначе строка ребёнка осталась бы без родителя и lineage потерялся бы.
 *   [tree] с непустым запросом раскрывает всё дерево, чтобы совпадения были
 *   видны сразу.
 * - **Current session.** Открытая сессия не теряется: цепочка её предков
 *   автоматически раскрывается, а подавленный сирота-субагент остаётся
 *   видимым, если это текущая сессия (или её предок).
 *
 * Policy ничего не знает об Activity/View: сопоставление с UI выполняет
 * ChatActivity (интеграция — отдельный шаг, здесь существующий UI не меняется).
 */
object SessionTreePolicy {

    /**
     * Фильтр плоского списка для поиска — применять ДО [build]. Пустой запрос
     * возвращает список как есть; иначе остаются совпадения по
     * `«title preview»` (как фильтр шторки в ChatActivity) и их предки within
     * списка (контекст lineage). Порядок исходного списка сохраняется.
     */
    fun filterForSearch(sessions: List<SessionItem>, query: String): List<SessionItem> {
        val q = query.trim()
        if (q.isEmpty()) return sessions
        val byId = sessions.associateBy { it.id }
        val included = mutableSetOf<String>()
        sessions.filter { matchesQuery(it, q) }.forEach { match ->
            var row: SessionItem? = match
            while (row != null && included.add(row.id)) {
                row = row.parentSessionId?.let { byId[it] }
            }
        }
        return sessions.filter { it.id in included }
    }

    /**
     * Строит дерево из плоского списка (уже отфильтрованного поиском, если он
     * активен). [expandedParents] — id раскрытых родительских сессий;
     * [currentSessionId] — открытая сессия (помечается [SessionTreeNode.isCurrent],
     * её предки раскрываются автоматически); [searchActive] — раскрыть всё
     * дерево (совпадения поиска не должны прятаться под свёрнутыми родителями).
     */
    fun build(
        sessions: List<SessionItem>,
        expandedParents: Set<String> = emptySet(),
        currentSessionId: String? = null,
        searchActive: Boolean = false,
    ): List<SessionTreeNode> {
        // Дедупликация по id (первое вхождение выигрывает) — защита от гонок списка.
        val byId = LinkedHashMap<String, SessionItem>()
        sessions.forEach { byId.putIfAbsent(it.id, it) }
        val items = byId.values.toList()

        // Цепочка предков открытой сессии: авто-раскрытие + защита от подавления.
        val currentAncestors = ancestorIds(currentSessionId, byId)
        val keepIds = currentAncestors + listOfNotNull(currentSessionId)

        // Сироты-субагенты (родитель вне списка) подавляются — паритет с WebUI;
        // текущая сессия и её предки не подавляются никогда.
        val suppressed = items
            .filter { it.isSubagent && it.id !in keepIds && isOrphan(it, byId) }
            .map { it.id }
            .toSet()

        val childrenByParent = items
            .filter { it.id !in suppressed }
            .mapNotNull { item -> structuralParent(item, byId)?.let { it.id to item } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, kids) -> kids.sortedByDescending { recencyKey(it) } }

        val expandedSet = expandedParents.toMutableSet()
        expandedSet += currentAncestors
        if (searchActive) expandedSet += items.map { it.id }

        fun node(item: SessionItem, depth: Int, seen: MutableSet<String>): SessionTreeNode {
            val kids = childrenByParent[item.id].orEmpty()
                .filter { seen.add(it.id) } // защита: каждый узел ровно один раз
                .map { node(it, depth + 1, seen) }
            return SessionTreeNode(
                session = item,
                depth = depth,
                children = kids,
                expanded = kids.isNotEmpty() && item.id in expandedSet,
                isCurrent = item.id == currentSessionId,
            )
        }

        val roots = items.filter { it.id !in suppressed && structuralParent(it, byId) == null }
        // Корни без родителя; затем сироты (родитель вне списка) — хвост, как в
        // drawerSessions; затем члены циклов (родитель в списке, но естественного
        // корня нет). Внутри категории — свежие сначала, субагенты после обычных.
        val trueRoots = roots.filter { it.parentSessionId.isNullOrBlank() }
        val orphanRoots = roots.filter { isOrphan(it, byId) }
        val cycleRoots = roots.filter {
            !it.parentSessionId.isNullOrBlank() && !isOrphan(it, byId)
        }
        val ordered = pinnedFirst(trueRoots.filterNot { it.isSubagent }) +
            pinnedFirst(trueRoots.filter { it.isSubagent }) +
            pinnedFirst(orphanRoots) +
            pinnedFirst(cycleRoots)
        return ordered.map { node(it, 0, mutableSetOf(it.id)) }
    }

    /**
     * Свежие сначала, но закреплённые — выше незакреплённых (A3).
     *
     * Закрепление иначе было бы невидимым: флаг строки приезжает от сервера
     * (`pinned`), а список сортировался только по свежести, и закреплённый диалог
     * мог остаться внизу. Порядок внутри каждой группы сохраняется неизменным
     * (сортировка устойчива), поэтому строки без флага `pinned` — а это весь
     * существующий список, пока пользователь ничего не закрепил — идут ровно так
     * же, как раньше.
     */
    private fun pinnedFirst(items: List<SessionItem>): List<SessionItem> =
        items.filter { it.pinned }.sortedByDescending { recencyKey(it) } +
            items.filterNot { it.pinned }.sortedByDescending { recencyKey(it) }

    /**
     * Композиция «поиск -> дерево»: [filterForSearch] ДО построения, при
     * непустом запросе дерево раскрывается целиком (searchActive).
     */
    fun tree(
        sessions: List<SessionItem>,
        query: String = "",
        expandedParents: Set<String> = emptySet(),
        currentSessionId: String? = null,
    ): List<SessionTreeNode> {
        val q = query.trim()
        return build(
            filterForSearch(sessions, q),
            expandedParents = expandedParents,
            currentSessionId = currentSessionId,
            searchActive = q.isNotEmpty(),
        )
    }

    /**
     * Плоская проекция дерева для списка: узел + его дети, когда раскрыт
     * (пре-порядок). Свернутый родитель скрывает всю свою ветку — дочерние
     * сессии не являются отдельными верхнеуровневыми чатами.
     */
    fun visibleRows(roots: List<SessionTreeNode>): List<SessionTreeNode> {
        val out = mutableListOf<SessionTreeNode>()
        fun walk(node: SessionTreeNode) {
            out += node
            if (node.expanded) node.children.forEach(::walk)
        }
        roots.forEach(::walk)
        return out
    }

    /** Совпадение строки с запросом — та же семантика, что у фильтра шторки ChatActivity. */
    private fun matchesQuery(session: SessionItem, query: String): Boolean =
        "${session.title.orEmpty()} ${session.preview.orEmpty()}".contains(query, ignoreCase = true)

    /** Ключ свежести строки — как в `ChatUiState.drawerSessions`. */
    private fun recencyKey(item: SessionItem): String = item.updatedAt ?: item.createdAt ?: ""

    /** Родитель в пределах списка; null, когда родителя нет или он вне списка. */
    private fun inScopeParent(item: SessionItem, byId: Map<String, SessionItem>): SessionItem? =
        item.parentSessionId?.takeIf { it.isNotBlank() }?.let { byId[it] }

    /** Родитель задан, но отсутствует в списке (parent out of scope). */
    private fun isOrphan(item: SessionItem, byId: Map<String, SessionItem>): Boolean =
        !item.parentSessionId.isNullOrBlank() && byId[item.parentSessionId] == null

    /**
     * Структурный родитель для группировки: null, когда родитель вне списка ИЛИ
     * узел сам является своим предком (цикл — естественного корня нет). Цикл
     * ВЫШЕ узла (узла в нём нет) дочернюю связь сохраняет: такой узел остаётся
     * ребёнком, а корнем станет член цикла.
     */
    private fun structuralParent(item: SessionItem, byId: Map<String, SessionItem>): SessionItem? {
        val parent = inScopeParent(item, byId) ?: return null
        val seen = mutableSetOf<String>()
        var cursor: SessionItem? = parent
        while (cursor != null) {
            if (cursor.id == item.id) return null // узел сам свой предок — корня нет
            if (!seen.add(cursor.id)) return parent // цикл выше узла: связь сохраняется
            cursor = inScopeParent(cursor, byId)
        }
        return parent
    }

    /** Id всех предков сессии в пределах списка (защита от циклов). */
    private fun ancestorIds(currentSessionId: String?, byId: Map<String, SessionItem>): Set<String> {
        val out = LinkedHashSet<String>()
        var cursor = currentSessionId?.let { byId[it] }?.let { inScopeParent(it, byId) }
        while (cursor != null && out.add(cursor.id)) {
            cursor = inScopeParent(cursor, byId)
        }
        return out
    }
}
