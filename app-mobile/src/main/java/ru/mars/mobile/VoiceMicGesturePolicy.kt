package ru.mars.mobile

/** Что делает тап по микрофону. */
enum class VoiceMicTapAction {
    /** Обычный голосовой ввод: запись одной реплики и отправка. */
    START_MANUAL_INPUT,

    /** Идёт ручная запись — тап её закрывает и отправляет распознанное. */
    STOP_MANUAL_INPUT,

    /** Перебивание: тишина немедленно, режим диалога остаётся включённым. */
    STOP_SPEECH,

    /** Режим включён, звука нет: закрыть текущую реплику сейчас (ход уйдёт по `Final`). */
    STOP_UTTERANCE,

    /** Занято распознаванием: второй сессии не открываем, статус уже объясняет почему. */
    IGNORE,
}

/** Что делает удержание микрофона. */
enum class VoiceMicHoldAction {
    /** Включить голосовой диалог (микрофон не закрывается между вопросами). */
    ENABLE_DIALOG,

    /** Сначала спросить разрешение на микрофон, потом включить (ответ придёт в onRequestPermissionsResult). */
    ENABLE_DIALOG_AFTER_PERMISSION,

    /** Выключить режим — жест выключения не должен теряться ни в одной фазе. */
    DISABLE_DIALOG,

    /** Идёт ручная запись: второй микрофон не открываем. */
    IGNORE,
}

/**
 * Жесты кнопки микрофона: тап и удержание в одном месте, чтобы «стоп» и «выключить режим»
 * не конфликтовали.
 *
 * Почему это отдельная политика. У кнопки два жеста и три роли одновременно: голосовой ввод,
 * перебивание и переключатель режима. Пока решения принимались по месту в Activity, получалось
 * так: тап в активном диалоге пытался открыть ВТОРУЮ запись (микрофон уже занят захватом →
 * «Микрофон недоступен»), а звук при этом продолжал играть. Здесь зафиксировано:
 * - в активном диалоге тап — это ВСЕГДА про звук: звучит — глушим ([VoiceMicTapAction.STOP_SPEECH]),
 *   молчит — закрываем реплику ([VoiceMicTapAction.STOP_UTTERANCE]). Ручной ввод в режиме
 *   недоступен: второй микрофон физически не открыть;
 * - пока идёт распознавание, тап ничего не делает ([VoiceMicTapAction.IGNORE]): иначе одно и
 *   то же нажатие запускало бы второе распознавание поверх первого;
 * - удержание в активном диалоге — всегда выключение ([VoiceMicHoldAction.DISABLE_DIALOG]),
 *   в любой фазе (слушаю/думаю/говорю): это единственный выход из режима, и он не должен
 *   зависеть от того, что цикл делает прямо сейчас;
 * - вне режима поведение прежнее: тап — запись, удержание — включение (с запросом разрешения,
 *   если его ещё нет).
 *
 * Класс чистый: ни Android, ни вида, ни звука — только решение, которое хост исполняет.
 */
class VoiceMicGesturePolicy {

    fun onTap(
        dialogActive: Boolean,
        speaking: Boolean = false,
        manualRecording: Boolean = false,
        transcribing: Boolean = false,
    ): VoiceMicTapAction = when {
        // Распознавание уже идёт: второе не запускаем ни в каком режиме.
        transcribing -> VoiceMicTapAction.IGNORE
        // Активный диалог: тап управляет звуком, а не микрофоном.
        dialogActive && speaking -> VoiceMicTapAction.STOP_SPEECH
        dialogActive -> VoiceMicTapAction.STOP_UTTERANCE
        manualRecording -> VoiceMicTapAction.STOP_MANUAL_INPUT
        else -> VoiceMicTapAction.START_MANUAL_INPUT
    }

    fun onHold(
        dialogActive: Boolean,
        permissionGranted: Boolean,
        manualRecording: Boolean = false,
    ): VoiceMicHoldAction = when {
        dialogActive -> VoiceMicHoldAction.DISABLE_DIALOG
        manualRecording -> VoiceMicHoldAction.IGNORE
        !permissionGranted -> VoiceMicHoldAction.ENABLE_DIALOG_AFTER_PERMISSION
        else -> VoiceMicHoldAction.ENABLE_DIALOG
    }
}
