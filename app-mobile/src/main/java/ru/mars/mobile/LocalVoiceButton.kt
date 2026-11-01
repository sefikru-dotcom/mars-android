package ru.mars.mobile

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Круглая кнопка голосового состояния локального чата: микрофон, 40 dp, без подписи.
 * Классические View, цвета только из [MarsTheme], без новых зависимостей.
 *
 * ## Почему иконка, а не капсула с текстом
 * Капсула «Голос» / «Слушаю» / «Думаю» / «Говорю» занимала 120 dp+ и висела отдельной
 * строкой под композером. Состояние теперь читается цветом заливки и иконкой микрофона,
 * а слово никуда не исчезло — оно ушло в `contentDescription`, то есть осталось доступным
 * скринридеру и при этом не занимает ни пикселя ширины.
 *
 * ## Шов интеграции
 * Конструктор принимает ровно те зависимости, которые есть у экрана, и все необязательные —
 * со значениями по умолчанию, поэтому координатор подключает блок одной правкой:
 *
 * ```kotlin
 * val voice = LocalVoiceButton(
 *     activity = this,
 *     onTranscript = { text -> composer.input.setText(text); composer.input.setSelection(text.length) },
 *     onState = { state -> state.notice?.let { presenter.setNotice(it) } },
 *     onOpenX = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)) },
 * )
 * capsule.addView(voice.root, LinearLayout.LayoutParams(dp(40), dp(40)))
 * // в onDestroy: voice.release()
 * ```
 *
 * Если [onOpenX] не дан, кнопка не предлагает «открыть настройки» и просто просит разрешение
 * заново; блок при этом рисуется — голосовой ввод от этого не перестаёт работать.
 *
 * ## Цвет — это состояние, а не украшение
 * - покой — поверхность [MarsTheme.SURFACE] с контуром [MarsTheme.BORDER], иконка
 *   [MarsTheme.TEXT_MUTED]: действие доступно, но ничего не происходит;
 * - «Слушаю» — акцент [MarsTheme.ACCENT]: микрофон открыт, человек говорит;
 * - «Думаю» — предупреждение [MarsTheme.STATUS_WARNING] и приглушённая кнопка: нажатие
 *   в этой фазе ничего не делает (остановка хода — дело композера);
 * - «Говорю» — инфо [MarsTheme.STATUS_INFO]: нажатие глушит озвучку.
 *
 * Иконка на цветной заливке — тёмная ([MarsTheme.BG]) там, где тон светлый (предупреждение,
 * инфо), и белая ([MarsTheme.TEXT_STRONG]) на акценте: так контраст не зависит от темы.
 * Стиль круга берётся из [MarsTheme.circleIconButton] — тот же, что у `sendButton` композера.
 *
 * @param activity хост (разрешения, контекст, настройки приложения);
 * @param onTranscript распознанный текст — экран пишет его в композер;
 * @param onState снимок состояния на каждое изменение: экран показывает `notice`, если хочет;
 * @param onOpenX открыть настройки приложения при отклонённом навсегда разрешении; `null` — нет;
 * @param asrEngine готовый порт распознавания (тесты/повторное использование); `null` — фабрика;
 * @param serverTts серверный синтез для озвучки; `null` — системный TTS.
 */
class LocalVoiceButton(
    private val activity: Activity,
    private val onTranscript: (String) -> Unit,
    private val onState: (LocalVoiceState) -> Unit,
    private val onOpenX: (() -> Unit)? = null,
    asrEngine: AsrEnginePort? = null,
    serverTts: ServerTtsClient? = null,
    /** Офлайн-синтез (Piper): ответ звучит им, а не системным роботом. */
    offlineTts: (() -> ru.mars.mobile.tts.OfflineTtsPort?)? = null,
) {

    private val context: Context = activity

    /**
     * Круглая кнопка-микрофон. Хост добавляет её в свой layout как есть.
     *
     * Размер задан самой вьюхой ([FixedCircleButton]), а не только layout-параметрами:
     * экран добавляет root с `wrap_content`, и без фиксированного `onMeasure` ширина
     * снова поехала бы за содержимым (иконка + padding). 40 dp — всегда.
     */
    val root: ImageButton = FixedCircleButton(context, dp(SIZE_DP)).apply {
        isClickable = true
        isFocusable = true
        minimumWidth = dp(SIZE_DP)
        minimumHeight = dp(SIZE_DP)
        layoutParams = LinearLayout.LayoutParams(dp(SIZE_DP), dp(SIZE_DP))
        setOnClickListener { onClick() }
    }

    /** Логика канала: кнопка её только рисует и пересылает жесты. */
    val controller: LocalVoiceController

    /** Фаза последнего рендера: фон пересобирается только при её смене. */
    private var lastPhase: LocalVoicePhase? = null

    private val main = Handler(Looper.getMainLooper())

    /** Кнопка уже освобождена: отложенный refresh не должен трогать движки. */
    private var released = false

    init {
        controller = LocalVoiceController(
            activity = activity,
            onTranscript = onTranscript,
            onState = { next ->
                render(next)
                onState(next)
            },
            asrEngine = asrEngine,
            serverTts = serverTts,
            offlineTts = offlineTts,
        )
        // Контроллер в конструкторе молчит, поэтому первый снимок рисуем сами, а проверку
        // доступности (разрешение, TTS-сервис) откладываем до конца конструктора: иначе колбэк
        // onState прилетит хосту раньше, чем он присвоит себе ссылку на кнопку.
        render(controller.state())
        main.post { if (!released) controller.refresh() }
    }

    /** Показать состояние. Единственная точка правды — снимок контроллера. */
    fun render(state: LocalVoiceState) {
        if (lastPhase != state.phase) applyLook(state)
        root.contentDescription = descriptionFor(state)
        // В «Думаю» нажатие ничего не делает — кнопка не должна обещать действие.
        root.isEnabled = state.phase != LocalVoicePhase.THINKING
        root.alpha = if (state.phase == LocalVoicePhase.THINKING) 0.65f else 1f
        root.visibility = if (state.visible) View.VISIBLE else View.GONE
    }

    // --- проброс к контроллеру (экран не обязан знать про него) -------------------------------

    /** Ход завершён: озвучить ответ, если он на голосовую реплику. */
    fun onAnswerCompleted(text: String?) = controller.onAnswerCompleted(text)

    /**
     * Начало ответа: ранний старт озвучки.
     *
     * Возвращает `true`, если ответ будет озвучен потоком, — тогда экран обязан звать
     * [onAnswerDelta] на каждой дельте и НЕ звать [onAnswerCompleted] в конце (иначе
     * ответ прозвучит дважды).
     */
    fun onAnswerStarted(): Boolean = controller.onAnswerStarted()

    /** Очередная дельта ответа: контроллер сам режет текст на удобные для синтеза куски. */
    fun onAnswerDelta(delta: String?): Boolean = controller.onAnswerDelta(delta)

    /** Модель начала с размышлений: короткая подсказка вместо тишины. */
    fun onReasoningStarted(): Boolean = controller.onReasoningStarted()

    /** Поток ответа закончился: закрыть озвучку, не повторяя сказанное. */
    fun onAnswerEnded() = controller.onAnswerEnded()

    /** Начался ход: прошлая озвучка не должна звучать поверх нового ответа. */
    fun onTurnStarted() = controller.onTurnStarted()

    /** Озвучить произвольный текст (пункт «Озвучить» у сообщения). */
    fun speakNow(text: String?) = controller.speakNow(text)

    /** Немедленная тишина. */
    fun stopSpeaking() = controller.stopSpeaking()

    fun onResume() = controller.onResume()

    fun onPause() = controller.onPause()

    /** Ответ системного запроса разрешения (`requestCode == REQUEST_RECORD_AUDIO`). */
    fun onPermissionResult(granted: Boolean) = controller.onPermissionResult(granted)

    /** Освободить микрофон, движок и озвучку (из `onDestroy` экрана). */
    fun release() {
        released = true
        controller.release()
    }

    // --- внутреннее ---------------------------------------------------------------------------

    private fun onClick() {
        val current = controller.state()
        // Разрешение отклонено навсегда: системный диалог больше не покажется. Ведём в настройки,
        // но только если хост дал такое действие — иначе обычный запрос разрешения.
        if (current.phase == LocalVoicePhase.IDLE &&
            !current.micPermissionGranted &&
            controller.permissionBlocked()
        ) {
            // Локальная переменная: умное приведение не срабатывает для nullable-поля
            // в составном условии, а `onOpenX()` без `?.invoke()` не компилируется.
            onOpenX?.invoke()
            return
        }
        controller.toggle()
    }

    /**
     * Перекрасить круг под фазу.
     *
     * Стиль берётся у [MarsTheme.circleIconButton] целиком (овальный фон, ripple, размер
     * иконки, padding) — здесь нет второй копии палитры и второй геометрии кнопки. Фабрика
     * сознательно не кэширует RippleDrawable, поэтому вызывается только на смене фазы, а не
     * на кадр стрима.
     */
    private fun applyLook(state: LocalVoiceState) {
        val look = lookFor(state.phase)
        val styled = MarsTheme.circleIconButton(
            context = context,
            drawableRes = R.drawable.ic_mic,
            contentDesc = descriptionFor(state),
            bg = look.bg,
            iconTint = look.iconTint,
            sizeDp = SIZE_DP,
            strokeColor = look.stroke,
        )
        root.background = styled.background
        root.setImageDrawable(styled.drawable)
        root.setImageTintList(styled.imageTintList)
        root.scaleType = ImageView.ScaleType.CENTER_INSIDE
        root.setPadding(
            styled.paddingLeft,
            styled.paddingTop,
            styled.paddingRight,
            styled.paddingBottom,
        )
        lastPhase = state.phase
    }

    /**
     * Палитра фазы: заливка, тон иконки, контур. В покое контур обязателен — кнопка того же
     * тона, что и капсула композера, без рамки растворилась бы в ней.
     */
    private fun lookFor(phase: LocalVoicePhase): Look = when (phase) {
        LocalVoicePhase.IDLE -> Look(
            bg = MarsTheme.SURFACE,
            iconTint = MarsTheme.TEXT_MUTED,
            stroke = MarsTheme.BORDER,
        )

        LocalVoicePhase.LISTENING -> Look(
            bg = MarsTheme.ACCENT,
            iconTint = MarsTheme.TEXT_STRONG,
            stroke = 0,
        )

        LocalVoicePhase.THINKING -> Look(
            bg = MarsTheme.STATUS_WARNING,
            iconTint = MarsTheme.BG,
            stroke = 0,
        )

        LocalVoicePhase.SPEAKING -> Look(
            bg = MarsTheme.STATUS_INFO,
            iconTint = MarsTheme.BG,
            stroke = 0,
        )
    }

    /**
     * Описание для скринридера: то самое слово, которое раньше стояло на капсуле, плюс
     * причина, если она есть. Текст состояния не теряется — он переехал сюда.
     */
    private fun descriptionFor(state: LocalVoiceState): String {
        val base = when (state.phase) {
            LocalVoicePhase.IDLE ->
                if (state.micPermissionGranted) {
                    "Голосовой ввод"
                } else {
                    "Голосовой ввод. Нет разрешения на микрофон"
                }

            LocalVoicePhase.LISTENING -> "Слушаю…"
            LocalVoicePhase.THINKING -> "Думаю…"
            LocalVoicePhase.SPEAKING -> "Говорю…"
        }
        val notice = state.notice
        return if (notice.isNullOrBlank()) base else base + ". " + notice
    }

    private fun dp(value: Int): Int = MarsTheme.dp(context, value)

    /** Пара «заливка + тон иконки + контур»: одно состояние, три носителя. */
    private data class Look(val bg: Int, val iconTint: Int, val stroke: Int)

    /**
     * Круг фиксированного размера.
     *
     * Экран добавляет [root] с `wrap_content`, поэтому одного `layoutParams` мало: ширина
     * решалась бы содержимым (иконка 24 dp + padding 10 dp = 44 dp). `onMeasure` с жёстким
     * размером делает ширину независимой и от текста, и от хоста — 40 dp в любом родителе.
     */
    private class FixedCircleButton(context: Context, private val sizePx: Int) : ImageButton(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(sizePx, sizePx)
        }
    }

    companion object {
        /** Тот же requestCode, что у контроллера: удобно сравнивать в `onRequestPermissionsResult`. */
        const val REQUEST_RECORD_AUDIO: Int = LocalVoiceController.REQUEST_RECORD_AUDIO

        /** Размер кнопки: как у `sendButton` композера — один ряд, один ритм. */
        private const val SIZE_DP = 40
    }
}
