package ru.mars.mobile

/**
 * Фаза голосового диалога так, как её видит человек.
 *
 * Три фазы, а не четыре состояния цикла: `THINKING` цикла покрывает и «распознаю реплику»,
 * и «Марс думает над ответом» — для человека это одно и то же ожидание, и различать их в
 * интерфейсе значит показывать деталь реализации.
 */
enum class VoiceDialogPhase {
    /** Режим выключен: кнопка в обычном нейтральном виде, статус не наш. */
    OFF,

    /** Слушаю: микрофон открыт, ждём реплику. */
    LISTENING,

    /** Думаю: реплика распознана/распознаётся, ответа ещё нет. */
    THINKING,

    /** Говорю: ответ звучит. */
    SPEAKING,
}

/**
 * Что показать на кнопке микрофона и в статусной строке.
 *
 * @property active режим включён (красная кнопка, статус принадлежит голосу);
 * @property iconRes иконка состояния — состояние не должно читаться только по цвету
 *   (дальтонизм и яркое солнце на улице);
 * @property label текстовая подпись состояния для статусной строки;
 * @property contentDescription подпись для TalkBack: действие + состояние;
 * @property stateDescription короткое состояние («Слушаю», «Думаю», «Говорю») для
 *   `AccessibilityNodeInfo.stateDescription`;
 * @property showsStopAffordance тап сейчас останавливает (звук или реплику) — подсказка в
 *   contentDescription, чтобы «стоп» не приходилось угадывать.
 */
data class VoiceDialogUi(
    val phase: VoiceDialogPhase,
    val active: Boolean,
    val iconRes: Int,
    val label: String,
    val contentDescription: String,
    val stateDescription: String,
    val showsStopAffordance: Boolean,
)

/**
 * Решения о виде голосового диалога — чистый JVM, без Android.
 *
 * Почему политика, а не `when` в Activity: у пользователя одно требование, но три разных
 * «где» — кнопка (цвет, иконка, подпись), статусная строка (текст) и доступность (действие,
 * а не только цвет). Разложить это по трём местам значит получить три расхождения; здесь
 * все три поля выводятся из одной фазы и проверяются одним тестом.
 *
 * Правила, зафиксированные в `VoiceDialogPresentationTest`:
 * - кнопка красная ВЕСЬ активный диалог, во всех трёх фазах, и никогда вне его: «красная
 *   всегда» — регресс хуже исходного состояния (пользователь перестаёт понимать, слушают ли
 *   его сейчас);
 * - состояние читается словом и иконкой, не только цветом: у LISTENING — микрофон,
 *   у THINKING — «…», у SPEAKING — динамик;
 * - размер кнопки не меняется между фазами ([BUTTON_SIZE_DP]): дёргающийся композер ломает
 *   попадание пальцем на POCO и сдвигает соседние кнопки;
 * - подписи короткие: статусная строка — одна строка, без переносов и без «нажмите».
 */
class VoiceDialogPresentationPolicy {

    /**
     * Фаза по состоянию цикла и факту звука.
     *
     * [loopState] — `null`, когда режим выключен (`VoiceDialogLoop` не активен): именно `null`,
     * а не `IDLE`, потому что `IDLE` — это ещё и «цикл создан, но не запущен», и путать его с
     * «выключен пользователем» значит оставить красную кнопку после выключения.
     */
    fun phaseFor(loopState: VoiceDialogLoop.State?, speaking: Boolean): VoiceDialogPhase = when {
        loopState == null -> VoiceDialogPhase.OFF
        // Звук — сильнейший сигнал: он слышен и тогда, когда цикл ещё формально LISTENING
        // (первая фраза зазвучала до перехода состояния).
        speaking -> VoiceDialogPhase.SPEAKING
        loopState == VoiceDialogLoop.State.LISTENING -> VoiceDialogPhase.LISTENING
        loopState == VoiceDialogLoop.State.THINKING -> VoiceDialogPhase.THINKING
        loopState == VoiceDialogLoop.State.SPEAKING -> VoiceDialogPhase.SPEAKING
        else -> VoiceDialogPhase.OFF
    }

    /** Полное описание вида по фазе. [iconRes] — идентификатор drawable (в тестах — любой int). */
    fun uiFor(
        phase: VoiceDialogPhase,
        iconRes: VoiceDialogIcons,
    ): VoiceDialogUi = when (phase) {
        VoiceDialogPhase.OFF -> VoiceDialogUi(
            phase = phase,
            active = false,
            iconRes = iconRes.mic,
            label = "",
            contentDescription = MIC_IDLE_DESCRIPTION,
            stateDescription = STATE_IDLE,
            showsStopAffordance = false,
        )
        VoiceDialogPhase.LISTENING -> VoiceDialogUi(
            phase = phase,
            active = true,
            iconRes = iconRes.mic,
            label = LABEL_LISTENING,
            contentDescription = "$MIC_ACTIVE_PREFIX$STATE_LISTENING$STOP_HINT_UTTERANCE",
            stateDescription = STATE_LISTENING,
            showsStopAffordance = true,
        )
        VoiceDialogPhase.THINKING -> VoiceDialogUi(
            phase = phase,
            active = true,
            iconRes = iconRes.thinking,
            label = LABEL_THINKING,
            contentDescription = "$MIC_ACTIVE_PREFIX$STATE_THINKING$STOP_HINT_DISABLE",
            stateDescription = STATE_THINKING,
            showsStopAffordance = false,
        )
        VoiceDialogPhase.SPEAKING -> VoiceDialogUi(
            phase = phase,
            active = true,
            iconRes = iconRes.speaking,
            label = LABEL_SPEAKING,
            contentDescription = "$MIC_ACTIVE_PREFIX$STATE_SPEAKING$STOP_HINT_SPEECH",
            stateDescription = STATE_SPEAKING,
            showsStopAffordance = true,
        )
    }

    companion object {
        /**
         * Размер кнопки не меняется между фазами: 34 dp — прежний размер композера.
         * Вынесен в константу, чтобы «сделать заметнее» не превратилось в +8 dp и сдвиг
         * соседних кнопок (POCO, узкий экран, min-width).
         */
        const val BUTTON_SIZE_DP = 34

        /** Толщина постоянной обводки активной кнопки (dp). */
        const val ACTIVE_STROKE_DP = 2

        // Подписи — только состояние. Статусная строка живёт над композером на узком экране
        // (POCO, 360 dp): подсказки про тап/удержание в тексте давали вторую строку и сдвигали
        // композер при каждой смене фазы. Подсказки остались в contentDescription (TalkBack).
        const val LABEL_LISTENING = "Слушаю…"
        const val LABEL_THINKING = "Думаю…"
        const val LABEL_SPEAKING = "Говорю…"

        const val STATE_IDLE = "Голосовой диалог выключен"
        const val STATE_LISTENING = "Слушаю"
        const val STATE_THINKING = "Думаю"
        const val STATE_SPEAKING = "Говорю"

        const val MIC_IDLE_DESCRIPTION = "Голосовой ввод, удержание — голосовой диалог"
        const val MIC_ACTIVE_PREFIX = "Голосовой диалог включён. "
        const val STOP_HINT_UTTERANCE = ". Тап — закрыть реплику, удержание — выключить диалог"
        const val STOP_HINT_SPEECH = ". Тап — остановить звук, удержание — выключить диалог"
        const val STOP_HINT_DISABLE = ". Удержание — выключить диалог"
    }
}

/**
 * Идентификаторы иконок состояния. Отдельный носитель, чтобы политика осталась чистой
 * (никаких `R.drawable.*` в JVM-тестах), а Activity подставляла настоящие ресурсы.
 */
data class VoiceDialogIcons(
    val mic: Int,
    val thinking: Int,
    val speaking: Int,
)
