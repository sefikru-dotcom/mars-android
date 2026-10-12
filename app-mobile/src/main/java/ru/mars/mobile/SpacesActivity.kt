package ru.mars.mobile

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.mars.network.api.MarsApi
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.repository.WorkspaceRepository

/**
 * Экран «Пространства» (Spaces/Workspaces): каталог `GET /api/workspaces`
 * со строкой «Авто» (серверный default) и явным выбором активного workspace.
 *
 * Слои: UI (этот класс) -> [SpacesPresenter] (состояния, offline-safe) ->
 * [SpacesPolicy] (чистые строки/тексты, JVM-тесты) -> [WorkspaceRepository]
 * (сеть) + [WorkspaceSelection]/[PrefsWorkspaceChoiceStorage] (persisted-выбор,
 * тот же prefs-файл `mars-workspace-choice`, что читает ChatActivity — выбор
 * сразу влияет на `[Workspace::v1]`-сентинел исходящих сообщений).
 *
 * Переход из меню: намеренно НЕ правит ChatActivity/SidebarNavPolicy (их
 * трогает другая волна). Публичная точка входа для интегратора —
 * [SpacesActivity.newIntent]; документ — docs/spaces-screen.md.
 * Кнопка «Назад»/системный back — finish() и возврат в вызвавший ChatActivity.
 */
class SpacesActivity : ComponentActivity() {

    private lateinit var store: AndroidKeyStoreCredentialStore
    private lateinit var storage: PrefsWorkspaceChoiceStorage
    private lateinit var presenter: SpacesPresenter

    private lateinit var progress: ProgressBar
    private lateinit var noticeView: TextView
    private lateinit var list: LinearLayout
    private lateinit var statusBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AndroidKeyStoreCredentialStore(this)
        storage = PrefsWorkspaceChoiceStorage(getSharedPreferences(WORKSPACE_PREFS, MODE_PRIVATE))
        val selection = WorkspaceSelection(storage)
        presenter = SpacesPresenter(
            selection = selection,
            explicitPickPath = { storage.load()?.path },
            hasCredential = { store.read() != null },
            fetch = {
                val credential = store.read()
                if (credential == null) null
                else WorkspaceRepository().getWorkspaces(MarsApi.BASE_URL, credential)
            },
            isOffline = ::isDeviceOffline,
        )
        setContentView(buildView())
        presenter.subscribe { render(it) }
        lifecycleScope.launch { presenter.load() }
    }

    private fun buildView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MarsTheme.BG)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16), systemBars.top + dp(10), dp(16), systemBars.bottom + dp(10))
            insets
        }

        // Header: back / title / progress / refresh (каркас как у SkillsActivity).
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        header.addView(
            MarsTheme.circleIconButton(this, R.drawable.ic_arrow_back, "Назад", bg = MarsTheme.SURFACE, iconTint = MarsTheme.TEXT)
                .apply { setOnClickListener { finish() } },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
        )
        header.addView(TextView(this).apply {
            text = SpacesPolicy.SCREEN_TITLE
            textSize = 22f
            setTextColor(MarsTheme.TEXT_STRONG)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            // Узкий экран POCO F5: длинный заголовок усекается, без overflow.
            maxLines = 1
        }, LinearLayout.LayoutParams(0, -2, 1f))
        progress = ProgressBar(this).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        header.addView(progress, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(8) })
        header.addView(
            MarsTheme.circleIconButton(this, R.drawable.ic_refresh, "Обновить", bg = MarsTheme.SURFACE, iconTint = MarsTheme.TEXT)
                .apply { setOnClickListener { lifecycleScope.launch { presenter.load() } } },
            LinearLayout.LayoutParams(dp(40), dp(40))
        )
        root.addView(header)

        // Offline/notice-плашка (видна только при state.notice).
        noticeView = TextView(this).apply {
            visibility = View.GONE
            textSize = 13f
            setTextColor(MarsTheme.STATUS_WARNING)
            background = MarsTheme.pill(this@SpacesActivity, MarsTheme.SURFACE_SUBTLE, MarsTheme.BORDER_MUTED)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            maxLines = 2
        }
        root.addView(noticeView, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // Контент: список строк + статус-блок (сообщение фазы / retry).
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(24), 0, 0)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(list, LinearLayout.LayoutParams(-1, -2))
            addView(statusBox, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(content, ViewGroup.LayoutParams(-1, -2))
            },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        return root
    }

    private fun render(state: SpacesUiState) {
        progress.visibility = if (state.phase == SpacesPhase.LOADING) View.VISIBLE else View.GONE

        if (state.notice != null) {
            noticeView.visibility = View.VISIBLE
            noticeView.text = state.notice
        } else {
            noticeView.visibility = View.GONE
        }

        val revealed = state.rows.isNotEmpty() && list.childCount == 0
        list.removeAllViews()
        state.rows.forEach { row -> list.addView(rowCard(row, reveal = revealed), cardMargin()) }

        statusBox.removeAllViews()
        state.message?.let { message ->
            statusBox.addView(TextView(this).apply {
                text = message
                textSize = 14f
                setTextColor(if (state.phase == SpacesPhase.ERROR || state.phase == SpacesPhase.NO_AUTH) MarsTheme.STATUS_WARNING else MarsTheme.TEXT_MUTED)
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(16), 0, dp(16), 0)
            })
        }
        if (state.canRetry) {
            statusBox.addView(TextView(this).apply {
                text = SpacesPolicy.RETRY_LABEL
                textSize = 14f
                setTextColor(MarsTheme.ACCENT_TEXT)
                gravity = Gravity.CENTER
                background = MarsTheme.button(this@SpacesActivity, MarsTheme.ACCENT_BG, radiusDp = 999, strokeColor = MarsTheme.ACCENT_BORDER)
                setPadding(dp(22), dp(10), dp(22), dp(10))
                isClickable = true
                isFocusable = true
                setOnClickListener { lifecycleScope.launch { presenter.load() } }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        }
    }

    private fun rowCard(row: SpaceRowUi, reveal: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = MarsTheme.card(
                this@SpacesActivity,
                if (row.isActive) MarsTheme.SURFACE_CARD else MarsTheme.SURFACE,
                radiusDp = 14,
                strokeColor = if (row.isActive) MarsTheme.ACCENT_BORDER else MarsTheme.BORDER,
            )
            isClickable = true
            isFocusable = true
            contentDescription = "${row.title}. ${if (row.isActive) "Активное пространство" else "Выбрать пространство"}"
            setOnClickListener {
                presenter.select(row.key)
            }
        }
        card.addView(ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@SpacesActivity, R.drawable.ic_nav_spaces))
            imageTintList = android.content.res.ColorStateList.valueOf(
                if (row.isActive) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_MUTED
            )
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = row.title
            textSize = 15f
            typeface = if (row.isActive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(if (row.isActive) MarsTheme.TEXT_STRONG else MarsTheme.TEXT)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        if (row.isServerDefault) {
            titleRow.addView(
                MarsDialog.pill(
                    this,
                    "По умолчанию",
                    bg = MarsTheme.SURFACE_SUBTLE,
                    fg = MarsTheme.TEXT_MUTED,
                    border = MarsTheme.BORDER_MUTED,
                ).apply {
                    textSize = 10f
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }
            )
        }
        texts.addView(titleRow)
        texts.addView(TextView(this).apply {
            text = row.subtitle
            textSize = 12f
            setTextColor(MarsTheme.TEXT_MUTED)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
        })
        card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))

        if (row.isActive) {
            card.addView(ImageView(this).apply {
                setImageDrawable(ContextCompat.getDrawable(this@SpacesActivity, R.drawable.ic_check))
                imageTintList = android.content.res.ColorStateList.valueOf(MarsTheme.ACCENT_TEXT)
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(8) })
        }

        if (reveal) applyRevealMotion(card)
        return card
    }

    /**
     * Короткий reveal списка (fade + slide-up) строго через [MotionPolicy]:
     * при системном «уменьшить движение» длительность схлопывается в 0 и
     * анимация не запускается вовсе. Кривая — decelerate (MarsMotion stance).
     */
    private fun applyRevealMotion(view: View) {
        val duration = MotionPolicy.durationFor(MotionCue.MESSAGE_REVEAL, isReducedMotion())
        if (duration <= 0) return
        view.alpha = 0f
        view.translationY = dp(8).toFloat()
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(duration.toLong())
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** Settings.Global.ANIMATOR_DURATION_SCALE — тот же источник, что в ChatActivity. */
    private fun isReducedMotion(): Boolean = MotionPolicy.isReducedMotion(
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    )

    /**
     * Синхронный снимок «нет сети» для offline-safe состояний: нет активного
     * network или у него нет INTERNET-способности (трактование как в
     * [NetworkConnectivityObserver]).
     */
    private fun isDeviceOffline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun cardMargin() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    private fun dp(v: Int) = MarsTheme.dp(this, v)

    companion object {
        /** Тот же prefs-файл, что и у workspace-чипа ChatActivity (общий выбор). */
        const val WORKSPACE_PREFS = "mars-workspace-choice"

        /**
         * Публичная точка входа для интегратора (например, пункт меню
         * ChatActivity/SidebarNavPolicy будущей волны):
         * `startActivity(SpacesActivity.newIntent(this))`. Back из SpacesActivity
         * возвращает в вызвавший экран.
         */
        fun newIntent(context: Context): Intent = Intent(context, SpacesActivity::class.java)
    }
}
