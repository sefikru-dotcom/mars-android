package ru.mars.mobile

/**
 * Гейт перерисовки ленты smart-чипов — чистая JVM-политика (без Android).
 *
 * [QuickChips.visibleChips] зависит ровно от трёх входов, а `renderSmartChips()`
 * вызывается из хвоста КАЖДОГО кадра стрима ([ChatActivity.finishTranscriptRender]):
 * сносит строку, инфлейтит LinearLayout + до семи Button с новыми GradientDrawable
 * и просит у композера новый layout ~20 раз в секунду, хотя состав чипов меняется
 * два-три раза за ход. Подпись входов позволяет пропустить перерисовку целиком.
 */
class QuickChipsRenderGate {

    /** Подпись входов ленты: статус хода, есть ли ответ ассистента, упал ли ход. */
    data class Signature(
        val status: ChatStatus,
        val hasAssistantReply: Boolean,
        val lastTurnFailed: Boolean,
    )

    private var last: Signature? = null

    /** true — ленту надо перерисовать; false — на экране уже ровно то же самое. */
    fun shouldRender(signature: Signature): Boolean {
        if (signature == last) return false
        last = signature
        return true
    }

    /** Сброс после переинфлейта разметки (activity пересоздана) или смены сессии. */
    fun invalidate() {
        last = null
    }
}
