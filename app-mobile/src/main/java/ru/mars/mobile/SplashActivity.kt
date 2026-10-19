package ru.mars.mobile

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.credential.AndroidKeyStoreCredentialStore

/**
 * Фирменная заставка запуска: фон Марса на полной яркости, планета в неоновом
 * ореоле и слово «МАРС».
 *
 * Зачем отдельная Activity, а не `windowBackground` у `MainActivity`: при
 * сохранённом credential `MainActivity` не рисует ни одного кадра — сразу
 * `openChat()` + `finish()`, поэтому бренд-экран физически негде показать. Здесь
 * экран существует как самостоятельный шаг: он гарантированно попадает в кадр,
 * уважает нижнюю границу показа ([SplashPolicy.MIN_VISIBLE_MS]) и сам решает,
 * куда идти дальше.
 *
 * Тайминги, кривая и порог пульсации не дублируются в этом файле: решения
 * приходят из [SplashPolicy], режим анимации — из [MarsPulsePolicy] через
 * [StatusDotPulse.resolveMode]. Здесь только Android-обвязка: чтение системных
 * флагов, аниматор и жизненный цикл.
 *
 * Жизненный цикл аниматора: старт в [onStart], cancel в [onStop] и [onDestroy].
 * Заставка короткая, но пользователь может уйти домой в момент пульсации —
 * аниматор на отсоединённом окне продолжал бы тикать вхолостую (батарея, лишние
 * кадры), поэтому он живёт ровно столько, сколько экран видим.
 */
class SplashActivity : ComponentActivity() {

    private lateinit var store: AndroidKeyStoreCredentialStore
    private val handler = Handler(Looper.getMainLooper())

    /** Слой-ореол: альфой этого drawable и управляет пульсация. */
    private var haloDrawable: GradientDrawable? = null
    private var pulseAnimator: ValueAnimator? = null

    private var credentialJob: Job? = null
    private var routeTick: Runnable? = null

    private var startedAtMs: Long = 0L
    private var targetReady = false
    private var hasCredential = false
    private var reducedMotion = false
    private var leaving = false
    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Тема приходит из манифеста (SplashTheme). Бары перекрашиваем в фон
        // заставки: на API 28–30 тема не задаёт цвет окон, и над картинкой
        // осталась бы полоса дефолтного цвета, ломающая «бесшовный» бренд-экран.
        window.statusBarColor = MarsTheme.BG
        window.navigationBarColor = MarsTheme.BG

        reducedMotion = MotionPolicy.isReducedMotion(animatorDurationScale())
        store = AndroidKeyStoreCredentialStore(this)
        setContentView(buildContent())

        startedAtMs = SystemClock.elapsedRealtime()
        // Дедлайн на случай, если чтение credential не вернётся вовсе (зависший
        // keystore): политика умеет сказать Go по MAX_VISIBLE_MS, но её нужно
        // переспросить — без этого тика заставка осталась бы на экране навсегда.
        handler.postDelayed({ scheduleRoute() }, SplashPolicy.MAX_VISIBLE_MS + FRAME_SLACK_MS)
        readCredential()
    }

    override fun onStart() {
        super.onStart()
        startPulse()
    }

    override fun onStop() {
        stopPulse()
        super.onStop()
    }

    override fun onDestroy() {
        stopPulse()
        routeTick?.let(handler::removeCallbacks)
        routeTick = null
        credentialJob?.cancel()
        super.onDestroy()
    }

    // --- Разметка ---------------------------------------------------------------------

    /**
     * Экран собирается кодом, как и остальной UI модуля: планета и слово стоят
     * по центру, ореол — отдельный View ПОД планетой, чтобы пульсировала только
     * его альфа, а сама планета и текст оставались непрозрачными и неподвижными.
     */
    private fun buildContent(): View {
        // Фон НЕ дублируется на корне: он уже пришёл из темы (SplashTheme ->
        // splash_window_bg). Второй экземпляр layer-list означал бы вторую
        // декодированную копию 1080x2400 — лишние мегабайты на старте, ради
        // которых нет ни одного видимого пикселя.
        val root = FrameLayout(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val haloSize = dp(HALO_SIZE_DP)
        val planetBox = FrameLayout(this)
        haloDrawable = haloDrawable().also { halo ->
            planetBox.addView(View(this).apply { background = halo },
                FrameLayout.LayoutParams(haloSize, haloSize, Gravity.CENTER))
        }
        val planetSize = dp(PLANET_SIZE_DP)
        planetBox.addView(ImageView(this).apply {
            setImageResource(R.drawable.mars_splash_planet)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, FrameLayout.LayoutParams(planetSize, planetSize, Gravity.CENTER))
        column.addView(planetBox, LinearLayout.LayoutParams(haloSize, haloSize))

        // @string/app_name в нижнем регистре («Марс») — на заставке нужен
        // капс-логотип, поэтому регистр поднимаем здесь, а не заводим вторую
        // строку-дубль с тем же смыслом.
        column.addView(TextView(this).apply {
            text = getString(R.string.app_name).uppercase()
            textSize = 30f
            setTextColor(MarsTheme.TEXT_STRONG)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.34f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(WORD_TOP_MARGIN_DP) })

        root.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        return root
    }

    /**
     * Ореол: радиальный градиент от акцентного красного к полной прозрачности.
     *
     * Цвет берётся из палитры ([MarsTheme.ACCENT]), а не литералом, и в самом
     * градиенте он НЕПРОЗРАЧНЫЙ: непрозрачность задаёт альфа drawable, которой
     * управляет пульсация. Если зашить полупрозрачность в цвета, аниматор
     * умножал бы её на свою — ореол стал бы тусклым и перестал читаться.
     */
    private fun haloDrawable(): GradientDrawable {
        val radius = dp(HALO_SIZE_DP) / 2
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(
                MarsTheme.ACCENT,
                MarsGradients.withAlpha(MarsTheme.ACCENT, 0)
            )
            gradientRadius = radius.toFloat()
            setGradientCenter(0.5f, 0.5f)
        }
    }

    // --- Пульсация --------------------------------------------------------------------

    /**
     * Запуск пульсации ореола по существующей политике.
     *
     * Аниматор линейный, потому что кривую (косинус) считает
     * [MarsPulsePolicy.alphaByteAt]; easing в аниматоре применился бы дважды и
     * превратил дыхание в рывок. Повторный вызов безопасен: на одном экране
     * живёт не больше одного цикла.
     */
    private fun startPulse() {
        val halo = haloDrawable ?: return
        if (pulseAnimator != null) return

        // Тёмная фаза выставляется всегда: в статичном режиме ореол должен
        // остаться в том же оттенке, что и в начале пульсации.
        halo.alpha = MarsPulsePolicy.alphaByteAt(0f, MarsPulsePolicy.ONLINE_MAX_ALPHA)

        val mode = StatusDotPulse.resolveMode(this)
        if (!SplashPolicy.shouldPulse(mode)) return
        val periodMs = SplashPolicy.pulsePeriodMs(reducedMotion)
        if (periodMs <= 0) return

        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = periodMs.toLong()
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                halo.alpha = MarsPulsePolicy.alphaByteAt(
                    animation.animatedFraction,
                    MarsPulsePolicy.ONLINE_MAX_ALPHA
                )
            }
            start()
        }
    }

    /** Гашение аниматора; null означает «цикла нет» — повторный вызов no-op. */
    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    // --- Маршрут ----------------------------------------------------------------------

    /**
     * Чтение credential в IO-диспетчере: `AndroidKeyStoreCredentialStore.read()`
     * — блокирующая операция (Binder + AES-GCM), и в UI-потоке она съела бы
     * первый кадр заставки, то есть ровно тот, ради которого экран и заведён.
     */
    private fun readCredential() {
        credentialJob = lifecycleScope.launch {
            hasCredential = withContext(Dispatchers.IO) { store.read() } != null
            targetReady = true
            scheduleRoute()
        }
    }

    /**
     * Переспрашивает [SplashPolicy.decisionFor] и либо назначает следующий тик,
     * либо уходит. Тик ставится на остаток из решения, а не на фиксированный
     * шаг: политика сама знает и нижнюю границу, и дедлайн ожидания.
     */
    private fun scheduleRoute() {
        if (leaving) return
        routeTick?.let(handler::removeCallbacks)
        val elapsed = SystemClock.elapsedRealtime() - startedAtMs
        when (val decision = SplashPolicy.decisionFor(elapsed, targetReady, reducedMotion)) {
            is SplashPolicy.Decision.Hold -> {
                val tick = Runnable { scheduleRoute() }
                routeTick = tick
                // Запас в один кадр: Hold(remaining) отсчитывается от «сейчас», а
                // кадр придёт позже; без запаса Go сработает на кадр позже срока.
                handler.postDelayed(tick, decision.remainingMs.toLong() + FRAME_SLACK_MS)
            }
            SplashPolicy.Decision.Go -> leave()
        }
    }

    /**
     * Уход: гасим экран за [SplashPolicy.fadeOutMs] и только потом открываем
     * целевой экран, иначе системный переход накладывается на ещё видимый
     * логотип и читается как рывок.
     */
    private fun leave() {
        if (leaving) return
        leaving = true
        routeTick?.let(handler::removeCallbacks)
        routeTick = null

        val fadeMs = SplashPolicy.fadeOutMs(reducedMotion)
        if (fadeMs <= 0) {
            navigate()
            return
        }
        // Гасим decorView, а не корневой контейнер: фон окна рисует сам DecorView,
        // и только его альфа гасит экран целиком — иначе картинка Марса осталась
        // бы висеть до finish() и исчезла рывком.
        window.decorView.animate()
            .alpha(0f)
            .setDuration(fadeMs.toLong())
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { navigate() }
            .start()
        // Страховка: если окно ушло в фон и аниматор не доиграл, маршрут всё
        // равно выполняется. navigate() идемпотентен.
        handler.postDelayed({ navigate() }, fadeMs.toLong() + FADE_GUARD_MS)
    }

    /** Открывает целевой экран по [SplashRoute]; повторный вызов игнорируется. */
    private fun navigate() {
        if (navigated) return
        navigated = true
        val target = when (SplashRoute.destinationFor(hasCredential)) {
            SplashRoute.Destination.CHAT -> ChatActivity::class.java
            SplashRoute.Destination.LOGIN -> MainActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }

    /** Системный масштаб анимаций — тот же источник reduce motion, что у остального UI. */
    private fun animatorDurationScale(): Float = Settings.Global.getFloat(
        contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f
    )

    private fun dp(value: Int) = MarsTheme.dp(this, value)

    private companion object {
        /** Диаметр ореола: планета занимает примерно треть — «мягкое свечение». */
        const val HALO_SIZE_DP = 320

        /** Диаметр планеты внутри ореола. */
        const val PLANET_SIZE_DP = 200

        /** Отступ слова «МАРС» от планеты. */
        const val WORD_TOP_MARGIN_DP = 18

        /** Один кадр при 60 Гц — запас для переспроса политики. */
        const val FRAME_SLACK_MS = 16L

        /** Запас на случай, если анимация гашения не доиграла. */
        const val FADE_GUARD_MS = 400L
    }
}
