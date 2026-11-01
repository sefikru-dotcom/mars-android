package ru.mars.mobile

import android.content.Context
import ru.mars.mobile.localtools.LocalDeviceTool
import ru.mars.mobile.localtools.LocalToolCatalog
import ru.mars.network.local.LocalToolCall
import ru.mars.network.local.LocalToolExecutor
import ru.mars.network.local.LocalToolResult
import ru.mars.network.local.LocalToolSchema

/**
 * Мост к инструментам телефона (`ru.mars.mobile.localtools`).
 *
 * Каталог — единственная точка сборки списка (контракт §4 W2.2). Экран не знает, какие
 * именно инструменты внутри: он берёт их схемы и исполняет вызов через [LocalToolExecutor].
 * Каталог может вернуть пустой список (сборка без инструментов, отключённый набор) —
 * тогда чат остаётся обычным текстовым, а не падает.
 *
 * Подтверждение деструктивных вызовов — здесь же, в обёртке исполнителя: ядро
 * (`LocalAgentLoop`) зовёт только [LocalToolExecutor] и не знает ни про диалог, ни
 * про флаги инструментов. Отказ человека — обычный `LocalToolResult(ok = false)`,
 * то есть модель видит причину и продолжает диалог.
 */
internal object LocalChatToolSource {

    /** Инструменты устройства. Сбой каталога — пустой список, а не крах экрана. */
    fun tools(context: Context): List<LocalDeviceTool> =
        runCatching { LocalToolCatalog.tools(context) }.getOrDefault(emptyList())

    /** Схемы для провайдера: ровно то, что объявили инструменты, в их порядке. */
    fun schemas(tools: List<LocalDeviceTool>): List<LocalToolSchema> = tools.map { it.schema }

    /**
     * Исполнитель с подтверждением.
     *
     * [confirm] вызывается **только** для инструментов с `requiresConfirmation = true`;
     * `false` — отказ (в том числе по таймауту диалога), и инструмент не исполняется.
     */
    fun executor(
        tools: List<LocalDeviceTool>,
        confirm: suspend (LocalDeviceTool, LocalToolCall) -> Boolean,
    ): LocalToolExecutor = LocalToolExecutor { call ->
        val tool = tools.firstOrNull { it.schema.name == call.name }
        when {
            tool == null -> LocalToolResult(
                ok = false,
                content = "инструмент ${call.name} недоступен на этом устройстве",
            )

            tool.requiresConfirmation && !confirm(tool, call) -> LocalToolResult(
                ok = false,
                content = "отклонено пользователем",
                preview = "Отклонено",
            )

            else -> try {
                tool.execute(call.argumentsJson)
            } catch (error: Exception) {
                // Инструмент обещает не бросать, но экран не должен зависеть от
                // чужой дисциплины: обрыв инструмента — это результат, а не крах хода.
                LocalToolResult(
                    ok = false,
                    content = "инструмент ${call.name} не выполнен: " +
                        (error.message ?: error.javaClass.simpleName),
                )
            }
        }
    }
}
