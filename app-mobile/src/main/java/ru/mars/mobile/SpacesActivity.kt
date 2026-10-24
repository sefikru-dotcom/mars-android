package ru.mars.mobile

import android.content.Context
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.mars.network.api.MarsApi
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.repository.WorkspaceRepository

/**
 * Экран «Пространства» — каталог рабочих пространств сервера
 * (`GET /api/workspaces`) и выбор того, в котором работают следующие сообщения
 * (паритет с чипом пространства в WebUI; TV-экран TvSpacesActivity умеет только
 * смотреть каталог — там нет мутаций, потому что device-токен покрывает лишь GET).
 *
 * Экран — раздел левого бара ([AppDestination.SPACES]) и сознательно
 * минималистичен: ни одной крупной кнопки, только шапка раздела
 * ([SectionScaffold]: назад, заголовок, прогресс, обновить), live-строка
 * счётчика и кликабельные строки каталога. Собственного тулбара и шторки у
 * экрана нет: левый бар живёт в навигаторе ([AppSectionsBar]), а раздел
 * открывается через [AppNavIntents.sectionIntent].
 *
 * Слои: решения о строках, метках и состояниях — чистый [SpacesPolicy];
 * загрузка и выбор — [SpacesPresenter] (единственный, кто пишет в
 * [WorkspaceSelection]); здесь — только разметка палитрой [MarsTheme] и
 * safe-area через shell. Выбор уходит в общее хранилище `mars-workspace-choice`,
 * поэтому чип чата подхватывает его при возврате (ChatActivity перечитывает
 * storage в onResume).
 */
class SpacesActivity : ComponentActivity() {

    private lateinit var store: AndroidKeyStoreCredentialStore
    private lateinit var selection: WorkspaceSelection
    private lateinit var presenter: SpacesPresenter

    private lateinit var scaffold: SectionScaffold.Parts
    private lateinit var body: LinearLayout

    /** Reveal каталога — один раз за экран: обновления не дёргают список заново. */
    private var revealed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AndroidKeyStoreCredentialStore(this)
        // То же хранилище выбора, что читает чип композера чата: иначе выбор на этом
        // экране не дошёл бы до исходящих сообщений.
        selection = WorkspaceSelection(
            PrefsWorkspaceChoiceStorage(
                getSharedPreferences(WORKSPACE_CHOICE_PREFS, Context.MODE_PRIVATE)
            )
        )
        presenter = SpacesPresenter(
            selection = selection,
            gateway = RepositorySpacesGateway(WorkspaceRepository(), MarsApi.BASE_URL) { store.read() },
            isOffline = ::isDeviceOffline,
        )
        setContentView(buildView())
        presenter.subscribe(::render)
        reload()
    }

    private fun buildView(): View {
        scaffold = SectionScaffold.create(this, SpacesPolicy.SCREEN_TITLE, ::reload)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Вложенный скролл только у списка: шапка и live-строка экрана остаются
            // на месте, длинный каталог прокручивается независимо.
            layoutParams = ViewGroup.LayoutParams(-1, -2)
        }
        scaffold.content.addView(
            ScrollView(this).apply {
                isFillViewport = true
                // Горизонтальный скролл запрещён: длинные пути усекаются внутри строки,
                // а не растягивают экран (POCO F5 — узкий портрет).
                isHorizontalScrollBarEnabled = false
                addView(body, ViewGroup.LayoutParams(-1, -2))
            },
            LinearLayout.LayoutParams(-1, 0, 1f),
        )
        return scaffold.root
    }

    private fun reload() {
        lifecycleScope.launch { presenter.load() }
    }

    private fun render(state: SpacesUiState) {
        scaffold.progress.visibility =
            if (state.phase == SpacesPhase.LOADING || state.refreshing) View.VISIBLE else View.GONE
        scaffold.statusLine.apply {
            // Плашка источника: кэш/офлайн — это не ошибка, а честная пометка данных,
            // поэтому она живёт в live-строке, а не заменяет список.
            val text = state.notice
            this.text = text.orEmpty()
            visibility = if (text == null) View.GONE else View.VISIBLE
        }

        body.removeAllViews()
        state.summary?.let { summary -> body.addView(summaryLine(summary)) }
        state.rows.forEach { row ->
            body.addView(
                spaceCard(row),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
            )
        }
        if (state.rows.isEmpty()) {
            // Фазы без строк: ровно один центральный блок, «Повторить» — только там,
            // где повтор осмыслен (нет пары — пара могла быть пройдена в другом экране).
            when (state.phase) {
                SpacesPhase.LOADING -> body.addView(
                    SectionStateViews.loading(this, SpacesPolicy.MSG_LOADING)
                )
                SpacesPhase.NO_AUTH -> body.addView(
                    SectionStateViews.error(this, SpacesPolicy.MSG_NO_AUTH, ::reload)
                )
                SpacesPhase.EMPTY -> body.addView(
                    SectionStateViews.empty(this, SpacesPolicy.MSG_EMPTY)
                )
                SpacesPhase.ERROR -> {
                    val retry: (() -> Unit)? = if (state.canRetry) ({ reload() }) else null
                    body.addView(
                        SectionStateViews.error(
                            this,
                            state.message ?: SpacesPolicy.MSG_ERROR,
                            retry,
                        )
                    )
                }
                SpacesPhase.READY -> Unit // строки есть — центральный блок не нужен
            }
        }
        if (state.rows.isNotEmpty() && !revealed) reveal(body)
    }

    /**
     * Live-строка счётчика каталога: «Пространств: N». Роль — текст, поэтому
     * озвучивается screen reader'ом после загрузки без перемещения фокуса.
     */
    private fun summaryLine(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(MarsTheme.TEXT_MUTED)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    }

    /**
     * Строка каталога: тап по всей строке выбирает пространство — отдельной
     * кнопки нет (владелец: минималистично, без крупных кнопок). Ripple и фон —
     * фабрика палитры, поэтому состояние нажатия видно и на тёмной теме.
     */
    private fun spaceCard(row: SpaceRow): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = MarsTheme.button(
                context,
                if (row.isActive) MarsTheme.ACCENT_BG else if (row.isAuto) MarsTheme.SURFACE_SUBTLE else MarsTheme.SURFACE_CARD,
                radiusDp = 12,
                strokeColor = if (row.isActive) MarsTheme.ACCENT_BORDER else MarsTheme.BORDER,
            )
            isClickable = true
            isFocusable = true
            // Подпись для screen reader собирается из тех же данных, что видны
            // на экране: имя, путь и метки (иначе «Активно» читалось бы только зрением).
            contentDescription = buildString {
                append(row.title)
                append(", ")
                append(row.subtitle)
                row.badges.forEach { badge ->
                    append(", ")
                    append(badge)
                }
            }
            setOnClickListener { presenter.select(row.key) }
        }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(
            TextView(this).apply {
                text = row.title
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (row.isActive) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_STRONG)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            // Вес забирает остаток строки: длинное имя не растягивает карточку вширь.
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        row.badges.forEach { label -> header.addView(badge(label, accent = row.isActive)) }
        card.addView(header)

        card.addView(
            TextView(this).apply {
                text = row.subtitle
                textSize = 12f
                setTextColor(MarsTheme.TEXT_MUTED)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
        )
        return card
    }

    private fun badge(label: String, accent: Boolean): TextView = TextView(this).apply {
        text = label
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (accent) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_MUTED)
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = MarsTheme.pill(
            context,
            if (accent) MarsTheme.ACCENT_BG else MarsTheme.SURFACE_SUBTLE,
            strokeColor = if (accent) MarsTheme.ACCENT_BORDER else MarsTheme.BORDER_SUBTLE,
        )
    }.also { view -> view.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) } }

    /** Появление каталога: короткий fade+slide, при «уменьшить движение» — без анимации. */
    private fun reveal(view: View) {
        revealed = true
        if (isReducedMotion()) return
        view.alpha = 0f
        view.translationY = dp(6).toFloat()
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(MotionPolicy.durationFor(MotionCue.MESSAGE_REVEAL, false).toLong())
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun isReducedMotion(): Boolean = MotionPolicy.isReducedMotion(
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    )

    /**
     * Офлайн-детектор для текста ошибки: «нет сети» и «сервер недоступен» —
     * разные причины, и подсказка пользователю тоже разная.
     */
    private fun isDeviceOffline(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = manager.activeNetwork ?: return true
        val caps = manager.getNetworkCapabilities(network) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun dp(value: Int): Int = MarsTheme.dp(this, value)
}
