package ru.mars.mobile

import android.graphics.Typeface
import android.os.Bundle
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
import ru.mars.network.repository.ProfilesRepository

/**
 * Экран «Профили»: список профилей сервера (`GET /api/profiles`) и активный
 * профиль — паритет с панелью профилей WebUI.
 *
 * UI-слой тонкий: состояние и состав строк — чистый [ProfilesPolicy],
 * чтение — [ProfilesRepository], рендер — палитра [MarsTheme], тексты — только
 * из `res/values/strings.xml`.
 *
 * Экран ТОЛЬКО ЧТЕНИЕ. Переключение профиля не выполняется: серверный switch
 * меняет `HERMES_HOME` живого процесса WebUI (то есть состояние чужих сессий),
 * это мутация чужого состояния и решение владельца. Выбора/кнопки «Использовать»
 * здесь нет намеренно.
 */
class ProfilesActivity : ComponentActivity() {

    private lateinit var store: AndroidKeyStoreCredentialStore
    private val repository = ProfilesRepository()
    private lateinit var presenter: ProfilesPresenter

    private lateinit var scaffold: SectionScaffold.Parts
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AndroidKeyStoreCredentialStore(this)
        presenter = ProfilesPresenter(
            hasCredential = { store.read() != null },
            fetch = { store.read()?.let { repository.getProfiles(MarsApi.BASE_URL, it) } },
            fetchActiveName = { store.read()?.let { repository.getActiveProfile(MarsApi.BASE_URL, it)?.name } },
        )
        setContentView(buildView())
        presenter.subscribe(::render)
        reload()
    }

    private fun buildView(): View {
        scaffold = SectionScaffold.create(this, getString(R.string.profiles_screen_title), ::reload)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scaffold.content.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(body, ViewGroup.LayoutParams(-1, -2))
            },
            LinearLayout.LayoutParams(-1, 0, 1f),
        )
        return scaffold.root
    }

    private fun reload() {
        lifecycleScope.launch { presenter.load() }
    }

    private fun render(state: ProfilesScreenState) {
        scaffold.progress.visibility = if (state is ProfilesScreenState.Loading) View.VISIBLE else View.GONE
        scaffold.statusLine.apply {
            val message = when {
                state is ProfilesScreenState.Body && state.stale -> getString(R.string.profiles_stale)
                state is ProfilesScreenState.Body && state.refreshing -> getString(R.string.profiles_loading)
                else -> null
            }
            text = message.orEmpty()
            visibility = if (message == null) View.GONE else View.VISIBLE
        }
        body.removeAllViews()
        when (state) {
            is ProfilesScreenState.Loading -> body.addView(
                SectionStateViews.loading(this, getString(R.string.profiles_loading))
            )
            is ProfilesScreenState.NoAuth -> body.addView(
                SectionStateViews.error(this, getString(R.string.profiles_failure_no_auth), ::reload)
            )
            is ProfilesScreenState.Failure -> body.addView(
                SectionStateViews.error(this, getString(R.string.profiles_failure_network), ::reload)
            )
            is ProfilesScreenState.Empty -> body.addView(
                SectionStateViews.empty(this, getString(R.string.profiles_empty))
            )
            is ProfilesScreenState.Body -> renderBody(state)
        }
    }

    private fun renderBody(state: ProfilesScreenState.Body) {
        body.addView(noticeCard(state))
        body.addView(
            TextView(this).apply {
                text = state.activeName?.let { getString(R.string.profiles_active_line, it) }
                    ?: getString(R.string.profiles_active_unknown)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (state.activeName != null) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_MUTED)
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(12) },
        )
        state.rows.forEach { row -> body.addView(profileCard(row), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }) }
        body.addView(
            TextView(this).apply {
                // Интерфейс read-only не по забывчивости: переключение профиля меняет
                // состояние живого процесса WebUI и выполняется только владельцем.
                text = getString(R.string.profiles_readonly_note)
                textSize = 12f
                setTextColor(MarsTheme.TEXT_MUTED)
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(8) },
        )
    }

    /** Пояснение режима: концепт-карточка WebUI либо пометка режима одного профиля. */
    private fun noticeCard(state: ProfilesScreenState.Body): View = TextView(this).apply {
        text = getString(
            if (state.singleProfileMode) R.string.profiles_notice_single_mode else R.string.profiles_notice_concept
        )
        textSize = 13f
        setTextColor(MarsTheme.TEXT_MUTED)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = MarsTheme.card(this@ProfilesActivity, MarsTheme.SURFACE_SUBTLE, strokeColor = MarsTheme.BORDER_SUBTLE)
    }.also { card ->
        card.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    }

    private fun profileCard(row: ProfilesRow): View {
        val accent = row.showActiveBadge
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = MarsTheme.card(
                context,
                if (accent) MarsTheme.ACCENT_BG else MarsTheme.SURFACE_CARD,
                strokeColor = if (accent) MarsTheme.ACCENT_BORDER else MarsTheme.BORDER,
            )
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(
            MarsTheme.statusDot(this, if (row.gatewayRunning) MarsTheme.STATUS_ONLINE else MarsTheme.TEXT_MUTED).apply {
                contentDescription = getString(
                    if (row.gatewayRunning) R.string.profiles_gateway_running else R.string.profiles_gateway_stopped
                )
            },
            LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) },
        )
        header.addView(
            TextView(this).apply {
                text = row.title
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (accent) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_STRONG)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            // Вес занимает остаток строки, поэтому длинное имя не растягивает карточку.
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        if (row.showActiveBadge) header.addView(badge(getString(R.string.profiles_active_badge), accent = true))
        if (row.showDefaultBadge) header.addView(badge(getString(R.string.profiles_default_badge), accent = false))
        if (row.showHiddenBadge) header.addView(badge(getString(R.string.profiles_hidden_badge), accent = false))
        card.addView(header)

        val metaPieces = ProfilesPolicy.metaParts(row) { enabled, total ->
            resources.getQuantityString(R.plurals.profiles_skills, total, enabled, total)
        }
        card.addView(
            TextView(this).apply {
                text = if (metaPieces.isEmpty()) {
                    getString(R.string.profiles_no_configuration)
                } else {
                    metaPieces.joinToString(getString(R.string.profiles_meta_separator))
                }
                textSize = 12f
                setTextColor(MarsTheme.TEXT_MUTED)
                maxLines = 2
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
    }.also { view ->
        view.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) }
    }

    private fun dp(value: Int): Int = MarsTheme.dp(this, value)
}
