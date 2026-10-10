package ru.mars.mobile

import ru.mars.network.model.TodoItem
import ru.mars.network.model.TodoSnapshot

data class TodoItemView(
    val id: String,
    val content: String,
    val status: String,
    val statusLabel: String,
    val isCompleted: Boolean,
    val isInProgress: Boolean,
)

data class TodoCardView(
    val visible: Boolean,
    val title: String,
    val completedCount: Int,
    val totalCount: Int,
    val inProgressContent: String?,
    val items: List<TodoItemView>,
) {
    companion object {
        val HIDDEN = TodoCardView(
            visible = false,
            title = "",
            completedCount = 0,
            totalCount = 0,
            inProgressContent = null,
            items = emptyList(),
        )
    }
}

object TodoPresenter {

    fun present(snapshot: TodoSnapshot?): TodoCardView {
        val todos = snapshot?.todos ?: return TodoCardView.HIDDEN
        if (todos.isEmpty()) return TodoCardView.HIDDEN

        val total = todos.size
        val completed = todos.count { it.status == "completed" }
        val inProgress = todos.firstOrNull { it.status == "in_progress" }

        val title = if (completed == total) {
            "Все задачи выполнены ($total/$total)"
        } else {
            "Задачи сессии: $completed/$total выполнено"
        }

        val items = todos.mapIndexed { index, item ->
            val id = item.id ?: (index + 1).toString()
            val (label, isDone, inProg) = when (item.status) {
                "completed" -> Triple("Выполнено", true, false)
                "in_progress" -> Triple("В процессе", false, true)
                "cancelled" -> Triple("Отменено", false, false)
                else -> Triple("Ожидает", false, false)
            }
            TodoItemView(
                id = id,
                content = item.content,
                status = item.status,
                statusLabel = label,
                isCompleted = isDone,
                isInProgress = inProg,
            )
        }

        return TodoCardView(
            visible = true,
            title = title,
            completedCount = completed,
            totalCount = total,
            inProgressContent = inProgress?.content,
            items = items,
        )
    }
}
