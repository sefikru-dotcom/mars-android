package ru.mars.mobile

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.widget.AppCompatImageButton
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Круглая кнопка голосового состояния локального чата: микрофон, 36 dp, без подписи.
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
 * capsule.addView(voice.root, LinearLayout.LayoutParams(dp(36), dp(36)))
 * // в onDestroy: voice.release()
 * ```
 *
 * Если [onOpenX] не дан, кнопка не предлагает «открыть настройки» и просто просит разрешение
 * заново; блок при этом рисуется — голосовой ввод от этого не перестаёт работать.
 *
 * ## Цвет — это состояние, а не украшение
 * - покой — тёмный стеклянный ореол [MarsTheme.COMPOSER_ACTION_BG] с гранью
 *   [MarsTheme.COMPOSER_ACTION_BORDER] и иконкой [MarsTheme.TEXT_EM]: ровно тот же тон,
 *   что у скрепки композера, — ряд кнопок читается как один;
 * - «Слушаю» — акцент [MarsTheme.ACCENT]: микрофон открыт, человек говорит;
 * - «Думаю» — предупреждение [MarsTheme.STATUS_WARNING]: реплика ушла, ответа ещё нет;
 * - «Говорю» — инфо [MarsTheme.STATUS_INFO]: нажатие глушит озвучку.
 *
 * Кнопка живая в ЛЮБОЙ фазе (см. [render]): в «Думаю» тап начинает новую реплику, в «Говорю» —
 * глушит ответ и сразу слушает. Мёртвая кнопка ломала непрерывный разговор в полноэкранном
 * режиме: человек ждал ответ, а нажать было нечего.
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
     * снова поехала бы за содержимым (иконка + padding). 36 dp — всегда.
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

    /**
     * Фаза, о смене которой уже сообщили тактильно и подписью.
     *
     * Отдельно от [lastPhase]: перерисовка и объявление — разные поводы. На кадр стрима
     * (partial-текст) фаза не меняется, и отклик не повторяется.
     */
    private var announcedPhase: LocalVoicePhase? = null

    private val main = Handler(Looper.getMainLooper())

    /** Кнопка уже освобождена: отложенный refresh не должен трогать движки. */
    private var released = false

    init {
        controller = LocalVoiceController(
            activity = activity,
            onTranscript = onTranscript,
            onState = { next ->
                // Снимок для экрана: смена фазы даёт тактильный отклик и понятную подпись
                // («Слушаю — говорите…» / «Запись завершена — распознаю…»), поэтому и в
                // локальном чате видно и чувствуется, когда говорить, а когда микрофон уже
                // закрыт. Причина контроллера (нет разрешения, микрофон занят) сильнее.
                val presented = present(next)
                render(presented)
                onState(presented)
            },
            asrEngine = asrEngine,
            serverTts = serverTts,
            offlineTts = offlineTts,
        )
        // Контроллер в конструкторе молчит, поэтому первый снимок рисуем сами, а проверку
        // доступности (разрешение, TTS-сервис) откладываем до конца конструктора: иначе колбэк
        // onState прилетит хосту раньше, чем он присвоит себе ссылку на кнопку.
        render(controller.state())
        main.post {
            if (!released) {
                controller.refresh()
                // Прогрев — сразу после refresh: экран уже знает про разрешение и доступность, а
                // модель распознавания (23 МиБ) начинает качаться при открытии чата, а не на
                // первом нажатии микрофона. Провал прогрева ничего не ломает — останется
                // системный резерв.
                controller.warmUpAsrInBackground()
            }
        }
    }

    /** Показать состояние. Единственная точка правды — снимок контроллера. */
    fun render(state: LocalVoiceState) {
        if (lastPhase != state.phase) applyLook(state)
        root.contentDescription = descriptionFor(state)
        // Кнопка остаётся кликабельной во всех фазах: тап в «Думаю» открывает новую реплику,
        // тап в «Говорю» глушит ответ и сразу слушает (см. [LocalVoiceController.toggle]).
        // Раньше в THINKING кнопка была выключена, и один пропущенный колбэк хоста оставлял
        // микрофон мёртвым до перезапуска экрана.
        root.isEnabled = true
        root.alpha = 1f
        root.visibility = if (state.visible) View.VISIBLE else View.GONE
    }

    /**
     * Прогреть офлайн-распознавание заранее — тихо, без запроса микрофона.
     *
     * Экран зовёт это при открытии локального чата: к первому тапу по микрофону модель
     * (23 МиБ) уже на месте, и человек не ждёт пятнадцать секунд с мёртвой кнопкой.
     */
    fun warmUp() = controller.warmUpAsrInBackground()

    /**
     * Снимок состояния для экрана: на смену фазы — тактильный отклик и подсказка-слово.
     *
     * Зачем копия, а не прямой проброс: [LocalVoiceController] описывает техническое
     * состояние, а человеку нужно действие («говорите», «распознаю»). Подсказка фазы
     * добавляется ТОЛЬКО когда контроллер не сказал ничего важнее (нет разрешения, микрофон
     * занят): его причина всегда конкретнее и не должна затираться.
     */
    private fun present(state: LocalVoiceState): LocalVoiceState {
        if (state.phase == announcedPhase) return state
        val previous = announcedPhase
        announcedPhase = state.phase
        // Локальный контроллер закрывает реплику переходом LISTENING → THINKING (ответ ещё
        // впереди), поэтому момент «микрофон перестал слушать» объявляется именно здесь.
        val hint = phaseHint(state.phase)
            ?: if (previous == LocalVoicePhase.LISTENING && state.phase == LocalVoicePhase.IDLE) {
                FINISHED_NOTICE
            } else {
                null
            }
        if (hint == null) return state
        // Момент «микрофон открылся / перестал слушать» чувствуется рукой: смотреть на экран
        // в этот момент человек не обязан.
        root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        return if (!state.notice.isNullOrBlank()) state else state.copy(notice = hint)
    }

    /** Подсказка фазы — то, что человеку нужно сделать или чего он ждёт прямо сейчас. */
    private fun phaseHint(phase: LocalVoicePhase): String? = when (phase) {
        LocalVoicePhase.LISTENING -> LISTENING_NOTICE
        LocalVoicePhase.THINKING -> THINKING_NOTICE
        LocalVoicePhase.SPEAKING -> SPEAKING_NOTICE
        LocalVoicePhase.IDLE -> null
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

    /**
     * Темп и голос озвучки.
     *
     * Прокси к контроллеру, а не отдельное состояние: кнопка — это лицо канала, и
     * экран, у которого на руках только она, не должен знать, что внутри есть
     * [LocalVoiceController]. `null` — вернуть умолчания.
     */
    fun setProsody(prosody: VoiceProsody?) = controller.setProsody(prosody)

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
            bg = MarsTheme.COMPOSER_ACTION_BG,
            iconTint = MarsTheme.TEXT_EM,
            stroke = MarsTheme.COMPOSER_ACTION_BORDER,
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
        val notice = state.notice?.takeIf { it.isNotBlank() }
        // Наша собственная подсказка фазы уже говорит и состояние, и что делать: не дублируем
        // («Слушаю…. Слушаю — говорите…» скринридер читал бы дважды).
        if (notice != null && notice == phaseHint(state.phase)) return notice
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
        return if (notice == null) base else base + ". " + notice
    }

    private fun dp(value: Int): Int = MarsTheme.dp(context, value)

    /** Пара «заливка + тон иконки + контур»: одно состояние, три носителя. */
    private data class Look(val bg: Int, val iconTint: Int, val stroke: Int)

    /**
     * Круг фиксированного размера.
     *
     * Экран добавляет [root] с `wrap_content`, поэтому одного `layoutParams` мало: ширина
     * решалась бы содержимым (иконка 24 dp + padding 10 dp = 44 dp). `onMeasure` с жёстким
     * размером делает ширину независимой и от текста, и от хоста — 36 dp в любом родителе.
     */
    private class FixedCircleButton(
        context: Context,
        private val sizePx: Int,
    ) : AppCompatImageButton(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(sizePx, sizePx)
        }
    }

    companion object {
        /** Тот же requestCode, что у контроллера: удобно сравнивать в `onRequestPermissionsResult`. */
        const val REQUEST_RECORD_AUDIO: Int = LocalVoiceController.REQUEST_RECORD_AUDIO

        /**
         * Подсказки фазы для экрана: что делать человеку, а не что делает движок.
         *
         * Тот же язык, что у кнопки микрофона в чате: «Слушаю — говорите…» и «Запись
         * завершена — распознаю…». В локальном чате не должно быть второго словаря состояний.
         */
        const val LISTENING_NOTICE = "Слушаю — говорите…"

        /**
         * Реплика закрыта, ответа ещё нет. Раньше здесь было «Запись завершена — распознаю…»,
         * но с этой волны THINKING — это ещё и ожидание модели, поэтому подсказка говорит о том,
         * что человек ждёт на самом деле.
         */
        const val THINKING_NOTICE = "Думаю над ответом…"
        const val SPEAKING_NOTICE = "Отвечаю вслух…"

        /** Реплика закрыта и текст уже в поле ввода: микрофон больше не слушает. */
        const val FINISHED_NOTICE = "Запись завершена — текст в поле ввода"

        /**
         * Размер кнопки: как у скрепки и `sendButton` композера — один ряд, один ритм
         * (36 dp; размер держит и layout-параметры, и `onMeasure` вьюхи).
         */
        private const val SIZE_DP = 36
    }
}
