package ru.mars.mobile

/**
 * Одно событие потокового распознавания речи.
 *
 * Контракт стабилен: на него подписан цикл голосового диалога (ChatActivity/VoiceDialogLoop
 * через [StreamingAsrEngine]), поэтому события описывают только то, что нужно UI:
 * «текст растёт», «реплика закончилась», «человек заговорил».
 *
 * Последовательность на одну реплику:
 * ```
 * SpeechStarted -> Partial(a) -> Partial(ab) -> ... -> Final(abc)
 * ```
 * [Partial] приходит на каждое изменение гипотезы (текст может укорачиваться и меняться),
 * [Final] — ровно один раз на реплику: либо по endpoint-детекции (человек замолчал), либо
 * по принудительному [StreamingAsrEngine.finishUtterance] (нажали «стоп»).
 */
sealed class AsrEvent {
    /** Частичный результат: текст может меняться. */
    data class Partial(val text: String) : AsrEvent()

    /** Финальный результат по endpoint-детекции (человек замолчал). */
    data class Final(val text: String) : AsrEvent()

    /** Речь началась (после тишины). */
    data object SpeechStarted : AsrEvent()
}
