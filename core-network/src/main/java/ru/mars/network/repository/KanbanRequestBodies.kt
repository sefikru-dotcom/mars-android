package ru.mars.network.repository

/**
 * Тела запросов записи канбан-доски — ЕДИНОЕ место, где зашита форма тела
 * (контракт сверен с `/opt/hermes-webui/api/kanban_bridge.py`: `_create_task_payload`,
 * `_patch_task_payload`, `_comment_payload`, `_task_action_payload`,
 * `_bulk_tasks_payload`, `_link_tasks_payload`).
 *
 * Правила, общие для всех билдеров:
 * - `title`/`body` комментария сервер обрезает и требует непустыми — пустое
 *   значение НЕ отправляем: сервер ответил бы 400 «title is required»;
 * - частичный PATCH семантичен: сервер меняет только ПРИСУТСТВУЮЩИЕ ключи
 *   (`if "title" in body`), поэтому опциональные поля попадают в тело лишь
 *   когда вызывающий явно их передал (null = «не трогать»);
 * - `assignee = ""` — осознанная команда «снять исполнителя» (сервер:
 *   `body.get("assignee") or None`), поэтому пустая строка отправляется, а
 *   null — нет;
 * - экранирование — общий для модуля строгий RFC 8259 [JsonStringEncoding]
 *   (сервер парсит тело Python `json.loads` в strict-режиме).
 *
 * Чистые JVM-функции без сети/Android — тела запросов сверяются посимвольно
 * в KanbanWritesRepositoryTest (записанные MockWebServer запросы).
 */
internal object KanbanRequestBodies {

    /** Строковое поле, если значение непустое после trim. */
    private fun text(parts: MutableList<String>, key: String, value: String?) {
        value?.trim()?.takeIf(String::isNotBlank)?.let { parts.add("\"$key\":${JsonStringEncoding.escape(it)}") }
    }

    /** Строковое поле «как есть» (пустая строка значима: снятие исполнителя). */
    private fun raw(parts: MutableList<String>, key: String, value: String?) {
        value?.let { parts.add("\"$key\":${JsonStringEncoding.escape(it)}") }
    }

    private fun number(parts: MutableList<String>, key: String, value: Int?) {
        value?.let { parts.add("\"$key\":$it") }
    }

    private fun bool(parts: MutableList<String>, key: String, value: Boolean?) {
        value?.let { parts.add("\"$key\":$it") }
    }

    private fun body(parts: List<String>): String = parts.joinToString(",", "{", "}")

    /**
     * `POST /api/kanban/tasks` — новая задача. Обязателен только `title`;
     * `assignee`/`tenant`/`priority`/`status` — по желанию. Доску задаёт
     * вызывающий (query `?board=`), в теле её нет: так же делает WebUI.
     */
    fun createTask(
        title: String,
        body: String? = null,
        assignee: String? = null,
        priority: Int? = null,
        tenant: String? = null,
        status: String? = null,
    ): String {
        val parts = mutableListOf<String>()
        text(parts, "title", title)
        text(parts, "body", body)
        text(parts, "assignee", assignee)
        number(parts, "priority", priority)
        text(parts, "tenant", tenant)
        text(parts, "status", status)
        return body(parts)
    }

    /**
     * `PATCH /api/kanban/tasks/{id}/patch` — частичное обновление. Все поля
     * опциональны, но пустое тело сервер примет как no-op, поэтому клиент
     * обязан передать хотя бы одно (это ответственность вызывающего).
     *
     * `status = "running"` сервер отклоняет (400): состояние `running`
     * принадлежит диспетчеру/claim-протоколу, а не UI.
     */
    fun patchTask(
        title: String? = null,
        body: String? = null,
        status: String? = null,
        assignee: String? = null,
        priority: Int? = null,
        tenant: String? = null,
        result: String? = null,
        summary: String? = null,
        blockReason: String? = null,
    ): String {
        val parts = mutableListOf<String>()
        text(parts, "title", title)
        raw(parts, "body", body)
        text(parts, "status", status)
        raw(parts, "assignee", assignee)
        number(parts, "priority", priority)
        text(parts, "tenant", tenant)
        text(parts, "result", result)
        text(parts, "summary", summary)
        text(parts, "block_reason", blockReason)
        return body(parts)
    }

    /** `POST /api/kanban/tasks/{id}/comments` — комментарий; `author` по умолчанию `webui`. */
    fun comment(body: String, author: String? = null): String {
        val parts = mutableListOf<String>()
        text(parts, "body", body)
        text(parts, "author", author)
        return body(parts)
    }

    /** `POST /api/kanban/tasks/{id}/block` — причина блокировки (может отсутствовать). */
    fun block(reason: String? = null): String {
        val parts = mutableListOf<String>()
        text(parts, "reason", reason)
        return body(parts)
    }

    /**
     * `POST /api/kanban/tasks/bulk` — одна мутация на список задач. Сервер
     * применяет `archive` ИЛИ `status` (archive приоритетнее), затем
     * `assignee`/`priority`, и отдаёт `results[]` по каждой задаче.
     */
    fun bulk(
        ids: List<String>,
        status: String? = null,
        archive: Boolean? = null,
        assignee: String? = null,
        priority: Int? = null,
    ): String {
        val parts = mutableListOf<String>()
        val cleanIds = ids.map(String::trim).filter(String::isNotEmpty).distinct()
        parts.add("\"ids\":[" + cleanIds.joinToString(",") { JsonStringEncoding.escape(it) } + "]")
        text(parts, "status", status)
        bool(parts, "archive", archive)
        raw(parts, "assignee", assignee)
        number(parts, "priority", priority)
        return body(parts)
    }

    /** `POST /api/kanban/links` (или `/links/delete`) — связь родитель → потомок. */
    fun link(parentId: String, childId: String): String =
        body(
            listOf(
                "\"parent_id\":${JsonStringEncoding.escape(parentId.trim())}",
                "\"child_id\":${JsonStringEncoding.escape(childId.trim())}",
            )
        )
}
