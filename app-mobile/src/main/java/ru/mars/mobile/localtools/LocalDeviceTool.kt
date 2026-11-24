package ru.mars.mobile.localtools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import ru.mars.network.local.LocalToolResult
import ru.mars.network.local.LocalToolSchema

/**
 * Инструмент, который локальный чат исполняет прямо на телефоне.
 *
 * Контракт свой, марсовский: `app.venus.*` сюда не тянется. Схема уходит провайдеру
 * как есть ([LocalToolSchema.parametersJson] — JSON Schema строкой), результат
 * возвращается текстом для модели.
 *
 * Правила для реализаций:
 *  - [execute] **никогда** не бросает наружу: любая ошибка — это
 *    `LocalToolResult(ok = false, content = "причина")`;
 *  - нет разрешения — тоже ошибка текстом, а не системный диалог: запрос разрешений
 *    живёт в UI, инструмент о нём не знает;
 *  - ничего не выполняется в фоне: контакты и календарь — короткий ContentResolver-запрос.
 *
 * [requiresConfirmation] — «спрашивать человека перед вызовом». Диалог показывает экран
 * (W3), инструмент только объявляет флаг: будильник, запись буфера, событие календаря.
 */
interface LocalDeviceTool {

    val schema: LocalToolSchema

    val requiresConfirmation: Boolean

    suspend fun execute(argumentsJson: String): LocalToolResult
}

/**
 * Разбор аргументов вызова.
 *
 * Пустая строка — пустой объект (провайдеры присылают `""` для инструментов без
 * параметров). Не объект или битый JSON — `null`, вызывающий отвечает понятной ошибкой.
 */
internal fun arguments(argumentsJson: String): JSONObject? {
    if (argumentsJson.isBlank()) return JSONObject()
    return runCatching { JSONObject(argumentsJson) }.getOrNull()
}

/** Единый текст ошибки для неразобранных аргументов. */
internal fun badArguments(): LocalToolResult = LocalToolResult(
    ok = false,
    content = "Аргументы должны быть JSON-объектом; разобрать строку не удалось",
)

internal fun toolOk(content: String): LocalToolResult = LocalToolResult(ok = true, content = content)

internal fun toolError(content: String): LocalToolResult = LocalToolResult(ok = false, content = content)

/**
 * Обёртка исполнения: гарантирует, что наружу не вылетит исключение.
 *
 * Один и тот же текст ошибки на все инструменты: модель должна видеть причину и
 * продолжать диалог, а не обрыв цикла. Стек и класс исключения — только в тексте
 * сообщения, без стека в лог: инструменты короткие, разбирать трейс некому.
 */
internal fun guarded(toolName: String, block: () -> LocalToolResult): LocalToolResult = try {
    block()
} catch (e: Exception) {
    toolError("Инструмент $toolName не выполнен: ${e.message ?: e.javaClass.simpleName}")
}

/**
 * То же для инструментов, которым нужен suspend внутри: сеть (генерация картинки),
 * файловый ввод-вывод на `Dispatchers.IO`. Обычная лямбда здесь не годится —
 * suspend-вызовы внутри неё компилятор не пропускает.
 */
internal suspend fun guardedSuspend(
    toolName: String,
    block: suspend () -> LocalToolResult,
): LocalToolResult = try {
    block()
} catch (e: kotlinx.coroutines.CancellationException) {
    // Отмену нельзя глотать: она обязана дойти до вызывающего, иначе корутина
    // продолжит работу после отмены и результат придёт в закрытый экран.
    throw e
} catch (e: Exception) {
    toolError("Инструмент $toolName не выполнен: ${e.message ?: e.javaClass.simpleName}")
}

/** Проверка разрешения без запроса: только «есть/нет». */
internal fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** Текст отказа, когда разрешения нет: имя разрешения модели полезнее адреса настроек. */
internal fun missingPermission(permission: String): LocalToolResult =
    toolError("нет разрешения ${permission.substringAfterLast('.')}")

/** Разрешение на чтение контактов. */
internal const val PERMISSION_READ_CONTACTS: String = Manifest.permission.READ_CONTACTS

/** Разрешение на чтение календаря. */
internal const val PERMISSION_READ_CALENDAR: String = Manifest.permission.READ_CALENDAR

/** Разрешение на запись в календарь. */
internal const val PERMISSION_WRITE_CALENDAR: String = Manifest.permission.WRITE_CALENDAR
