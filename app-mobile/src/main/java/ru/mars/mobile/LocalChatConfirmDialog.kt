package ru.mars.mobile

import android.app.Activity
import android.app.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Подтверждение действия в стиле [MarsDialog]: «Разрешить» / «Отклонить».
 *
 * Зачем отдельный объект: подтверждают два экрана (деструктивный инструмент в диалоге
 * и удаление диалога/профиля), и оба обязаны вести себя одинаково — BACK и таймаут
 * означают ОТКАЗ, а не согласие («молчание ≠ согласие»).
 *
 * Таймаут [TIMEOUT_MS] = 120 с по контракту §4 W3.5: экран не должен висеть в
 * ожидании вечно, если человек ушёл, а окно осталось.
 */
object LocalChatConfirmDialog {

    /** Предел ожидания ответа. По истечении — «Отклонено». */
    const val TIMEOUT_MS = 120_000L

    /**
     * Показать модалку и дождаться ответа. `true` — разрешено, `false` — отказ,
     * BACK, таймаут или экран уже закрыт.
     */
    suspend fun ask(
        activity: Activity,
        title: String,
        body: String,
        confirmLabel: String = "Разрешить",
        denyLabel: String = "Отклонить",
        code: String? = null,
        codeLabel: String? = null,
        timeoutMs: Long = TIMEOUT_MS,
    ): Boolean = withContext(Dispatchers.Main) {
        if (activity.isFinishing || activity.isDestroyed) return@withContext false

        var dialog: Dialog? = null
        val answer = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                val answered = AtomicBoolean(false)
                val finish: (Boolean) -> Unit = { allowed ->
                    // Ровно один ответ: кнопка, BACK и таймаут приходят конкурентно,
                    // а повторный resume уронил бы корутину хода.
                    if (answered.compareAndSet(false, true)) {
                        runCatching { dialog?.dismiss() }
                        if (continuation.isActive) runCatching { continuation.resume(allowed) }
                    }
                }
                dialog = MarsDialog.show(activity, onCancel = { finish(false) }) { root ->
                    root.addView(MarsDialog.header(activity, title, null))
                    root.addView(MarsDialog.paragraph(activity, body))
                    code?.let { block ->
                        MarsDialog.codeBlock(activity, codeLabel, block)?.let { root.addView(it) }
                    }
                    val allow = MarsDialog.primaryButton(activity, confirmLabel) { finish(true) }
                    val deny = MarsDialog.denyButton(activity, denyLabel) { finish(false) }
                    root.addView(MarsDialog.buttonRow(activity, allow, deny))
                    listOf(allow, deny)
                }
                // Отмена корутины (кнопка «Стоп», уход с экрана) обязана закрыть окно:
                // иначе модалка остаётся висеть над уже завершённым ходом.
                continuation.invokeOnCancellation { runCatching { dialog?.dismiss() } }
            }
        }
        if (answer == null) {
            runCatching { dialog?.dismiss() }
            false
        } else {
            answer
        }
    }
}
