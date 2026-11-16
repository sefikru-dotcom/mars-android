package ru.mars.mobile

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.text.TextUtils
import android.widget.TextView

/**
 * Полноэкранный голосовой режим КАК СЛОЙ поверх экрана диалога, а не отдельная Activity.
 *
 * ## Почему не Activity (и это главное решение файла)
 * Первая версия была отдельным экраном ([VoiceModeActivity]) — и она ломала сама себя:
 * при её старте `LocalChatActivity` уходит в `onPause`, а там вызывается
 * `voice.onPause()`, который **отменяет слушание и глушит озвучку**. То есть человек
 * нажимал «разговор», экран открывался, и микрофон закрывался тем же кадром. Обойти это
 * можно только флагом «мы открываем свой экран, не глуши», то есть исключением в чужой
 * логике.
 *
 * Слой решает это без исключений: хост-активность остаётся на экране, её жизненный цикл
 * не меняется, микрофон и синтез продолжают принадлежать тому же [LocalVoiceController].
 * Второго микрофона, второго TTS и гонки за аудиофокус не возникает по построению.
 *
 * ## Что слой делает и чего не делает
 * Рисует снимок [VoiceModeState] (переводит его в текст и цвет) и отдаёт нажатия хосту.
 * Ни движков, ни разрешений, ни собственного состояния: закрыл — всё осталось как было.
 *
 * [VoiceModeActivity] остаётся в дереве как самостоятельный экран для случая, когда
 * голос нужен без диалога (например, с плитки быстрых действий). Этот класс от неё
 * независим: логика подписей живёт в [VoiceModePresenter] и у них общая.
 */
internal class VoiceModeOverlay(activity: Activity) {

    /** Куда слой отдаёт нажатия. Все вызовы — с UI-потока. */
    interface Callbacks {
        /** Основная кнопка: начать или закончить реплику. */
        fun onMicTap()

        /** Основная кнопка во время озвучки: немедленная тишина. */
        fun onStopSpeech()

        /** Крестик и системный «назад»: свернуть режим. */
        fun onClose()

        /** Вторая кнопка после неудачного хода. */
        fun onRetry()

        /**
         * Тап по языковой пилюле: открыть выбор направления перевода.
         *
         * Слой не выбирает языки сам: список, запрет «тот же язык» и промпт живут в
         * [TranslatePolicy], а шторка — в [TranslateSheet]. Здесь только кнопка.
         */
        fun onPickDirection()
    }

    private val context: Activity = activity

    var callbacks: Callbacks? = null

    /** Включён ли слой сейчас: хост решает по нему, перехватывать ли «назад». */
    var isShowing: Boolean = false
        private set

    private val orb = VoiceModeOrbView(context)

    private val phaseLabel = TextView(context).apply {
        textSize = 17f
        gravity = Gravity.CENTER
        setTextColor(MarsTheme.TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** Строка подсказки: причина отказа или «можно повторить». Пустая — не рисуется. */
    private val hintLabel = TextView(context).apply {
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(MarsTheme.TEXT_MUTED)
        visibility = View.GONE
    }

    /** Услышанная реплика. Показывается, чтобы человек видел, что его поняли. */
    private val userLine = TextView(context).apply {
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(MarsTheme.TEXT_EM)
        visibility = View.GONE
    }

    /** Начало ответа: целиком он читается на слух, экран не должен стать лентой. */
    private val assistantLine = TextView(context).apply {
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(MarsTheme.TEXT_MUTED)
        visibility = View.GONE
    }

    private val primaryButton = pill().apply {
        setOnClickListener {
            val screen = VoiceModePresenter.render(lastState, transcript = showTranscript)
            when (screen.action) {
                VoiceModeAction.START, VoiceModeAction.FINISH -> callbacks?.onMicTap()
                VoiceModeAction.STOP -> callbacks?.onStopSpeech()
                VoiceModeAction.NONE -> Unit
            }
        }
    }

    /**
     * Контейнер кнопок: вторая («Повторить») показывается только после неудачи, и
     * удобнее скрывать весь блок, чем считать его высоту вручную.
     */
    private val buttons = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private val retryButton = pill(heightDp = SECONDARY_HEIGHT_DP).apply {
        text = RETRY_LABEL
        setOnClickListener { callbacks?.onRetry() }
    }

    /** Крестик: свернуть. Текстовая кнопка, а не иконка: иконки в проекте — свои drawable. */
    private val closeButton = TextView(context).apply {
        text = CLOSE_LABEL
        textSize = 15f
        setTextColor(MarsTheme.TEXT_MUTED)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = CLOSE_DESCRIPTION
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener { close() }
    }

    /**
     * Пилюля направления перевода: «Перевод выкл.» или «Рус → Англ».
     *
     * Стоит слева от крестика, а не в нижнем блоке: язык — это режим, в котором идёт
     * разговор, и он должен быть виден всё время, а не только когда человек дотянется
     * до нижних кнопок. Доступность зеркалит состояние: скринридер читает, включён ли
     * перевод, а не только пару языков.
     */
    private val directionLabel = TextView(context).apply {
        textSize = 13f
        setTextColor(MarsTheme.TEXT_MUTED)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        // min-width: 0 — иначе длинная подпись распирает шапку слоя.
        minimumWidth = 0
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { callbacks?.onPickDirection() }
    }

    /** Показывать ли текст реплик. Слух — основной канал, но видеть услышанное важно. */
    private var showTranscript: Boolean = true

    /** Последний снимок: нужен кнопке, чтобы понять, что делать при нажатии. */
    private var lastState: VoiceModeState = VoiceModeState()

    /**
     * Корень слоя. Непрозрачный фон [MarsTheme.BG]: сквозь него видна была бы лента
     * диалога, и «полный экран» превратился бы в подкрашенную копию чата.
     */
    val root: FrameLayout = FrameLayout(context).apply {
        setBackgroundColor(MarsTheme.GLASS_PANEL)
        visibility = View.GONE
        isClickable = true
        // Фон перехватывает тапы: иначе нажатие мимо кнопки проваливалось бы в чат.
        setOnClickListener { }
    }

    init {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        column.addView(
            orb,
            LinearLayout.LayoutParams(dp(ORB_DP), dp(ORB_DP)).apply { topMargin = dp(24) },
        )
        column.addView(phaseLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(20)
        })
        column.addView(hintLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        column.addView(userLine, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(20)
        })
        column.addView(assistantLine, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        buttons.addView(
            primaryButton,
            LinearLayout.LayoutParams(MATCH_PARENT, dp(PRIMARY_HEIGHT_DP)).apply { topMargin = dp(28) },
        )
        buttons.addView(
            retryButton,
            LinearLayout.LayoutParams(MATCH_PARENT, dp(SECONDARY_HEIGHT_DP)).apply { topMargin = dp(10) },
        )
        buttons.visibility = View.GONE
        column.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Колонка прижата к низу: орб в верхней половине, кнопка под большим пальцем.
        root.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = dp(32)
            leftMargin = dp(24)
            rightMargin = dp(24)
        })
        // Исходное состояние — «выключен»: до первого показа слоя направление ещё
        // не выбрано, и подпись не должна обещать перевод, которого нет.
        renderDirection(null)
        root.addView(directionLabel, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = dp(16)
            leftMargin = dp(12)
        })
        root.addView(closeButton, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(16)
            rightMargin = dp(8)
        })
    }

    /**
     * Подпись языковой пары. null — перевод выключен («Перевод выкл.»).
     *
     * Отдельным методом, а не полем состояния: направление живёт в экране и сохраняется
     * в настройках, слой о нём не помнит — иначе после поворота экрана подпись
     * разошлась бы с тем, что реально уходит в промпт.
     */
    fun renderDirection(direction: TranslateDirection?) {
        val label = if (direction == null) {
            DIRECTION_OFF
        } else {
            TranslatePolicy.shortLabel(direction)
        }
        directionLabel.text = label
        directionLabel.contentDescription = if (direction == null) {
            DIRECTION_OFF_DESCRIPTION
        } else {
            "Перевод: ${TranslatePolicy.directionLabel(direction)}. Нажмите, чтобы изменить"
        }
        directionLabel.setTextColor(
            if (direction == null) MarsTheme.TEXT_MUTED else MarsTheme.ACCENT,
        )
        // Пока переводим — цвет акцента: режим отличается от обычного разговора на глаз.
        directionLabel.visibility = View.VISIBLE
    }

    /** Показать слой поверх [parent] (обычно `scaffold.root`). Идемпотентно. */
    fun show(parent: ViewGroup) {
        if (parent.indexOfChild(root) < 0) {
            parent.addView(root, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        root.visibility = View.VISIBLE
        isShowing = true
        render(lastState)
    }

    /** Свернуть слой. Состояние диалога и микрофона не трогается: это дело хоста. */
    fun hide() {
        root.visibility = View.GONE
        isShowing = false
        // Орб гаснет: невидимый слой не должен крутить кадры в фоне (батарея).
        orb.render(VoiceModeTone.IDLE, pulses = false)
    }

    /** Крестик: сначала сообщаем хосту (он может остановить речь), потом прячемся. */
    private fun close() {
        callbacks?.onClose()
        hide()
    }

    /**
     * Отрисовать снимок. Идемпотентно и безопасно до показа: снимок копится в [lastState]
     * и применяется в момент [show].
     */
    fun render(state: VoiceModeState) {
        lastState = state
        val screen = VoiceModePresenter.render(state, transcript = showTranscript)
        phaseLabel.text = screen.phaseLabel
        phaseLabel.setTextColor(toneColor(screen.tone))
        orb.render(screen.tone, pulses = state.orbPulses)

        showText(hintLabel, screen.hint)
        showText(userLine, screen.userLine)
        showText(assistantLine, screen.assistantLine)

        primaryButton.text = screen.primaryLabel
        primaryButton.isEnabled = screen.primaryEnabled
        primaryButton.alpha = if (screen.primaryEnabled) 1f else DISABLED_ALPHA
        primaryButton.background = pillBackground(screen.primaryEnabled)
        primaryButton.contentDescription = screen.contentDescription

        buttons.visibility = View.VISIBLE
        retryButton.visibility = if (screen.showRetry) View.VISIBLE else View.GONE
    }

    /** Амплитуда микрофона 0..1: орб «дышит» по речи. */
    fun setLevel(level: Float) = orb.setLevel(level)

    /** Экран ушёл в фон: кадры останавливаются, состояние слоя сохраняется. */
    fun onPause() = orb.render(VoiceModeTone.IDLE, pulses = false)

    /** Экран вернулся: снимок применяется заново (орб снова дышит, если есть фаза). */
    fun onResume() = render(lastState)

    /**
     * Цвет фазы. Роли взяты из палитры статусов, потому что отдельной роли «слушаю» нет:
     * зелёный — канал открыт, предупреждение — ждём модель, инфо — говорит.
     */
    private fun toneColor(tone: VoiceModeTone): Int = when (tone) {
        VoiceModeTone.IDLE -> MarsTheme.TEXT_MUTED
        VoiceModeTone.LISTENING -> MarsTheme.STATUS_ONLINE
        VoiceModeTone.THINKING -> MarsTheme.STATUS_WARNING
        VoiceModeTone.SPEAKING -> MarsTheme.STATUS_INFO
    }

    /** Текст либо прячется целиком: пустая строка держала бы высоту и рвала ритм. */
    private fun showText(view: TextView, text: String?) {
        if (text.isNullOrBlank()) {
            view.visibility = View.GONE
        } else {
            view.text = text
            view.visibility = View.VISIBLE
        }
    }

    private fun pill(heightDp: Int = PRIMARY_HEIGHT_DP): TextView = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 16f
        setTextColor(MarsTheme.TEXT_STRONG)
        isClickable = true
        isFocusable = true
        minHeight = dp(heightDp)
        background = pillBackground(enabled = true)
    }

    private fun pillBackground(enabled: Boolean) = MarsTheme.pill(
        context,
        if (enabled) MarsTheme.ACCENT_BG else MarsTheme.SURFACE_SUBTLE,
        strokeColor = if (enabled) MarsTheme.ACCENT_BORDER else MarsTheme.BORDER,
    )

    private fun dp(value: Int): Int = MarsTheme.dp(context, value)

    private companion object {
        /** Подпись, когда перевод выключен: режим виден, но не мешает. */
        const val DIRECTION_OFF = "Перевод выкл."

        /** Описание для скринридера: что произойдёт по нажатию. */
        const val DIRECTION_OFF_DESCRIPTION = "Перевод выключен. Нажмите, чтобы включить"

        const val ORB_DP = 232
        const val PRIMARY_HEIGHT_DP = 56
        const val SECONDARY_HEIGHT_DP = 48
        const val DISABLED_ALPHA = 0.65f
        const val CLOSE_LABEL = "Свернуть"
        const val CLOSE_DESCRIPTION = "Свернуть голосовой режим"
        const val RETRY_LABEL = "Повторить"
    }
}
