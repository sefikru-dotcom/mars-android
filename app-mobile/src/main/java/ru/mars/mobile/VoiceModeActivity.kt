package ru.mars.mobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Полноэкранный голосовой режим: орб, строка фазы и одна кнопка.
 *
 * ## Зачем отдельный экран, если голос уже есть в композере
 * В композере голос — это кнопка 40 dp рядом с полем ввода: человек говорит, но
 * смотрит на клавиатуру и на текст. Разговор глазами не читается: непонятно, слышит
 * ли микрофон, думает ли модель, говорит ли она. Полный экран убирает всё лишнее и
 * оставляет три вещи, которые в этот момент важны, — живое пятно (слышит/думает/
 * говорит), слово фазы и кнопку.
 *
 * ## Чего экран НЕ делает (и почему это главное)
 * Он не открывает микрофон, не создаёт распознаватель, не заводит синтез и не знает
 * про разрешения. Всё это уже живёт в `LocalVoiceController` и его хозяине —
 * композере. Второй набор движков на втором экране означал бы два открытых микрофона,
 * два TTS-сервиса и гонку за аудиофокус: на практике — «эхо» и «микрофон занят».
 * Поэтому экран только РИСУЕТ снимок [VoiceModeState] и отдаёт четыре нажатия через
 * [Callbacks]; [callbacks] необязательны, и без них экран спокойно рисует состояние.
 *
 * ## Почему `android.app.Activity`, а не `ComponentActivity`
 * Экрану не нужны `ViewModel`, `OnBackPressedDispatcher` и `registerForActivityResult`:
 * у него нет ни движков, ни разрешений, ни состояния, которое надо переживать
 * поворотом (снимок приходит снаружи и придёт снова). Голый `Activity` здесь — не
 * экономия, а честная граница: если экрану однажды понадобится своя логика, это
 * будет признаком, что он забрал чужую работу.
 *
 * ## Шов интеграции
 * ```kotlin
 * val voiceMode = VoiceModeActivity.intentFor(this, transcript = true)
 * startActivity(voiceMode)
 * // после старта:
 * modeActivity.callbacks = object : VoiceModeActivity.Callbacks {
 *     override fun onMicTap() = voice.toggle()          // существующий LocalVoiceController
 *     override fun onStopSpeech() = voice.stopSpeaking()
 *     override fun onClose() = Unit
 *     override fun onRetry() = retryLastTurn()
 * }
 * modeActivity.render(VoiceModeState.from(voice.state()))
 * ```
 * В манифест нужна одна строка: `<activity android:name=".VoiceModeActivity" />`.
 */
class VoiceModeActivity : Activity() {

    companion object {
        /** Показывать ли текст реплик. По умолчанию выключен: разговор — на слух. */
        const val EXTRA_TRANSCRIPT: String = "transcript"

        /**
         * Намерение на открытие режима.
         *
         * Транскрипт — флаг, а не отдельный экран: это одна и та же сцена, у которой
         * есть или нет две строки текста. Второй Activity означал бы вторую копию
         * разметки и третью — при первом же изменении орба.
         */
        fun intentFor(context: Context, transcript: Boolean = false): Intent =
            Intent(context, VoiceModeActivity::class.java).putExtra(EXTRA_TRANSCRIPT, transcript)

        /** Габарит орба: на 232 dp «дыхание» видно с расстояния вытянутой руки. */
        private const val ORB_DP = 232

        /** Высота основной кнопки: тап-цель с запасом, как у кнопок композера. */
        private const val PRIMARY_HEIGHT_DP = 56

        /** Высота второй кнопки: она реже и не должна спорить с основной. */
        private const val SECONDARY_HEIGHT_DP = 48

        /** Прозрачность выключенной кнопки: видно, что она есть, но не нажимается. */
        private const val DISABLED_ALPHA = 0.65f
    }

    /**
     * Куда экран отдаёт нажатия. Все методы вызываются на UI-потоке.
     *
     * Отдельный интерфейс, а не четыре лямбды: у экрана ровно четыре действия, и
     * держать их одной ссылкой проще, чем четыре nullable-поля, каждое из которых
     * надо проверять перед вызовом.
     */
    interface Callbacks {
        /** Основная кнопка в покое и во время слушания: начать/закончить реплику. */
        fun onMicTap()

        /** Основная кнопка во время озвучки: немедленная тишина. */
        fun onStopSpeech()

        /** Крестик и системный «назад»: экран попросили свернуть. */
        fun onClose()

        /** Вторая кнопка после неудачного хода. */
        fun onRetry()
    }

    /**
     * Хозяин голосового канала. `null` — экран работает как витрина состояния:
     * кнопки нажимаются, но ничего не происходит, и ничего не падает.
     */
    var callbacks: Callbacks? = null

    private lateinit var orb: VoiceModeOrbView
    private lateinit var phaseLabel: TextView
    private lateinit var hintLabel: TextView
    private lateinit var userLine: TextView
    private lateinit var assistantLine: TextView
    private lateinit var primaryButton: TextView
    private lateinit var retryButton: TextView

    /** Показывать ли строки реплик: приходит из намерения и больше не меняется. */
    private var transcriptEnabled = false

    /** Последний снимок: перерисовывается целиком, поэтому хранить его достаточно. */
    private var state = VoiceModeState()

    /** Последний посчитанный экран: из него берётся действие кнопки. */
    private var screen: VoiceModeScreen? = null

    /** Разметка построена. До этого [render] только копит снимок. */
    private var viewsReady = false

    /** Состояние кнопки в прошлом кадре: фон пересобирается только при смене. */
    private var lastPrimaryEnabled: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        transcriptEnabled = intent.getBooleanExtra(EXTRA_TRANSCRIPT, false)
        setContentView(buildScreen())
        viewsReady = true
        // Снимок мог прийти до onCreate (хост зовёт render сразу после startActivity) —
        // применяем то, что накопилось, иначе первый кадр был бы пустым.
        apply(state)
    }

    /**
     * Показать состояние. Единственная точка правды — снимок снаружи.
     *
     * Метод идемпотентен: повторный вызов с тем же снимком не пересобирает фоны и не
     * трогает анимацию. Это важно, потому что хост может звать его на каждое событие
     * контроллера, в том числе на дельты распознавания.
     */
    fun render(state: VoiceModeState) {
        this.state = state
        if (!viewsReady) return
        apply(state)
    }

    /**
     * Амплитуда микрофона 0..1: орб «дышит» по речи.
     *
     * Отдельный метод, а не поле снимка: уровень меняется десятки раз в секунду, а
     * снимок — на смену фазы. Смешивать их значило бы пересобирать тексты и фоны на
     * каждый кадр громкости.
     */
    fun setLevel(level: Float) {
        if (viewsReady) orb.setLevel(level)
    }

    // --- сборка разметки -----------------------------------------------------------------------

    /**
     * Разметка собирается кодом, как на остальных экранах проекта: ни одного нового
     * ресурса и ни одной новой зависимости. Верх — «свернуть», центр — орб и тексты,
     * низ — кнопки; центр забирает всё свободное место, поэтому кнопка всегда у
     * большого пальца, а орб не прижат к шапке.
     */
    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MarsTheme.SCREEN_VEIL)
            setPadding(dp(24), dp(8), dp(24), dp(24))
        }

        root.addView(buildHeader(), LinearLayout.LayoutParams(MATCH_PARENT, dp(52)))

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        orb = VoiceModeOrbView(this)
        center.addView(orb, LinearLayout.LayoutParams(dp(ORB_DP), dp(ORB_DP)))

        phaseLabel = TextView(this).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            // Фаза меняется сама, без действия человека: live-region — единственный
            // способ сообщить об этом скринридеру, не отбирая фокус.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        center.addView(phaseLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(6)
        })

        hintLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(MarsTheme.TEXT_MUTED)
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        center.addView(hintLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        userLine = TextView(this).apply {
            textSize = 16f
            setTextColor(MarsTheme.TEXT)
            gravity = Gravity.CENTER
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        }
        center.addView(userLine, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(20)
        })

        assistantLine = TextView(this).apply {
            textSize = 15f
            setTextColor(MarsTheme.TEXT_EM)
            gravity = Gravity.CENTER
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        }
        center.addView(assistantLine, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        root.addView(center, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        retryButton = pill(SECONDARY_HEIGHT_DP).apply {
            text = VoiceModePresenter.RETRY_LABEL
            textSize = 15f
            setTextColor(MarsTheme.TEXT)
            background = MarsTheme.button(this@VoiceModeActivity, MarsTheme.SURFACE_SUBTLE, 999, MarsTheme.BORDER)
            visibility = View.GONE
            setOnClickListener { callbacks?.onRetry() }
        }
        bottom.addView(retryButton, LinearLayout.LayoutParams(MATCH_PARENT, dp(SECONDARY_HEIGHT_DP)).apply {
            bottomMargin = dp(10)
        })

        primaryButton = pill(PRIMARY_HEIGHT_DP).apply {
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { dispatchPrimary() }
        }
        bottom.addView(primaryButton, LinearLayout.LayoutParams(MATCH_PARENT, dp(PRIMARY_HEIGHT_DP)))

        root.addView(bottom, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return MarsSpaceBackgroundView.wrap(this, root)
    }

    /** Шапка: только «свернуть» — на этом экране больше некуда идти. */
    private fun buildHeader(): View {
        val header = FrameLayout(this)
        val close = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_close,
            "Свернуть голосовой режим",
            bg = MarsTheme.SURFACE,
            iconTint = MarsTheme.TEXT,
            sizeDp = 44,
            strokeColor = MarsTheme.BORDER,
        ).apply { setOnClickListener { close() } }
        header.addView(
            close,
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL),
        )
        return header
    }

    /**
     * Основа пилюли-кнопки: прямоугольник с закруглением капсулы, центрированный
     * текст и тап-цель на всю высоту. Фон ставится отдельно — он зависит от фазы.
     */
    private fun pill(heightDp: Int): TextView = TextView(this).apply {
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        minHeight = dp(heightDp)
    }

    // --- применение снимка ---------------------------------------------------------------------

    /**
     * Перенести [VoiceModeScreen] в View.
     *
     * Ни одной формулировки здесь нет: все строки пришли из [VoiceModePresenter].
     * Экран не имеет права придумывать текст, иначе одна и та же фраза жила бы в
     * двух местах и расходилась бы при правке.
     */
    private fun apply(state: VoiceModeState) {
        val screen = VoiceModePresenter.render(state, transcriptEnabled)
        this.screen = screen

        phaseLabel.text = screen.phaseLabel
        phaseLabel.setTextColor(toneColor(screen.tone))
        showText(hintLabel, screen.hint)
        showText(userLine, screen.userLine)
        showText(assistantLine, screen.assistantLine)

        primaryButton.text = screen.primaryLabel
        primaryButton.isEnabled = screen.primaryEnabled
        primaryButton.alpha = if (screen.primaryEnabled) 1f else DISABLED_ALPHA
        if (lastPrimaryEnabled != screen.primaryEnabled) {
            applyPrimaryLook(screen.primaryEnabled)
            lastPrimaryEnabled = screen.primaryEnabled
        }

        retryButton.visibility = if (screen.showRetry) View.VISIBLE else View.GONE

        orb.render(screen.tone, state.orbPulses)
        orb.contentDescription = screen.contentDescription
    }

    /**
     * Фон основной кнопки.
     *
     * Два вида, а не один с прозрачностью: выключенная кнопка акцентного цвета всё
     * равно читается как «нажми меня». Приглушённая поверхность говорит правду —
     * действие сейчас недоступно.
     */
    private fun applyPrimaryLook(enabled: Boolean) {
        if (enabled) {
            primaryButton.background = MarsTheme.button(this, MarsTheme.ACCENT, 999)
            primaryButton.setTextColor(MarsTheme.TEXT_STRONG)
        } else {
            primaryButton.background = MarsTheme.card(this, MarsTheme.SURFACE_SUBTLE, 999, MarsTheme.BORDER, 1)
            primaryButton.setTextColor(MarsTheme.TEXT_MUTED)
        }
    }

    /**
     * Цвет строки фазы: роли `STATUS_*`, как требует контракт волны.
     *
     * Покой — единственный серый: пока микрофон закрыт, подсвечивать нечего. В
     * остальных фазах цвет — тот же смысл, что у кнопки композера («думаю» —
     * предупреждение, «говорю» — инфо), поэтому два экрана не спорят о палитре.
     * «Слушаю» — зелёный `STATUS_ONLINE`, а не акцент: зелёный читается как «канал
     * открыт», тогда как рубин в этом приложении означает действие и опасность.
     */
    private fun toneColor(tone: VoiceModeTone): Int = when (tone) {
        VoiceModeTone.IDLE -> MarsTheme.TEXT_MUTED
        VoiceModeTone.LISTENING -> MarsTheme.STATUS_ONLINE
        VoiceModeTone.THINKING -> MarsTheme.STATUS_WARNING
        VoiceModeTone.SPEAKING -> MarsTheme.STATUS_INFO
    }

    /** Текст либо прячется целиком: пустая строка держала бы высоту и рвала ритм. */
    private fun showText(view: TextView, text: String?) {
        if (text == null) {
            view.visibility = View.GONE
            view.text = ""
        } else {
            view.text = text
            view.visibility = View.VISIBLE
        }
    }

    // --- нажатия -------------------------------------------------------------------------------

    /**
     * Основная кнопка. Действие берётся из посчитанного экрана, а не из фазы: правило
     * «что делает кнопка» живёт в [VoiceModePresenter], и второй `when` здесь был бы
     * второй копией этого правила.
     */
    private fun dispatchPrimary() {
        when (screen?.action) {
            VoiceModeAction.START, VoiceModeAction.FINISH -> callbacks?.onMicTap()
            VoiceModeAction.STOP -> callbacks?.onStopSpeech()
            VoiceModeAction.NONE, null -> Unit
        }
    }

    /** Свернуть: сначала сообщаем хозяину (он глушит звук), потом закрываемся сами. */
    private fun close() {
        callbacks?.onClose()
        finish()
    }

    /**
     * Системный «назад» равен крестику: у экрана нет другого выхода, и два разных
     * поведения на два жеста запутали бы хозяина канала.
     *
     * Метод платформы помечен `@Deprecated` в API 33 (`OnBackInvokedDispatcher`), но
     * здесь он единственный доступный без androidx.activity: экран не заводит
     * `OnBackPressedDispatcher` ради одного вызова.
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        close()
    }

    private fun dp(value: Int): Int = MarsTheme.dp(this, value)

}