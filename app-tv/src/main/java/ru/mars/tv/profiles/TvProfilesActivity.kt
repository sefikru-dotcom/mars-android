package ru.mars.tv.profiles

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.ProfilesResponse
import ru.mars.network.repository.ProfilesRepository
import ru.mars.tv.R
import ru.mars.tv.TvChatActivity
import ru.mars.tv.TvFocusLift
import ru.mars.tv.TvMotionPolicy
import ru.mars.tv.TvOfflineCacheStatus

/**
 * Экран «Профили» для Android TV — read-only каталог профилей сервера
 * (`GET /api/profiles`), паритет мобильного `ProfilesActivity` в части просмотра.
 *
 * Только чтение: экран показывает профили, их мету и активный профиль, а
 * единственное действие — OK на карточке открывает чат этого профиля
 * ([TvChatActivity] + [TvProfilesRouting.EXTRA_PROFILE]). Создания,
 * переименования, удаления и переключения профиля здесь нет: switch меняет
 * `HERMES_HOME` живого процесса WebUI, то есть состояние чужих сессий.
 *
 * D-pad: каждая карточка фокусируема и кликабельна (весь ряд — цель пульта,
 * мелких тапов нет); фокус восстанавливается по индексу после перерисовки
 * ([TvProfilesPolicy.restoreSelection]) — автообновление не сбрасывает пульт на
 * первую строку. Модальных перекрытий и ловушек фокуса экран не создаёт.
 *
 * Слои: состояние — [TvProfilesController] (seam + offline-кэш), решения и тексты
 * строк — [TvProfilesPolicy], палитра — ресурсы темы (`@color/mars_*`,
 * `@drawable/bg_focus_card`), размеры — `@dimen/tv_*`.
 */
class TvProfilesActivity : AppCompatActivity(), TvProfilesSurface {

    private lateinit var store: AndroidKeyStoreCredentialStore
    private lateinit var controller: TvProfilesController

    private lateinit var statusView: TextView
    private lateinit var loadingGroup: View
    private lateinit var errorGroup: View
    private lateinit var errorText: TextView
    private lateinit var dataScroll: View
    private lateinit var emptyText: TextView
    private lateinit var profilesContainer: LinearLayout

    /** Индекс карточки под фокусом: переживает перерисовку (стабильный фокус). */
    private var selectedIndex = TvProfilesPolicy.NO_SELECTION

    /** Системная настройка «уменьшить движение»: читается один раз на экран. */
    private val reducedMotion: Boolean by lazy {
        TvMotionPolicy.isReducedMotion(
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AndroidKeyStoreCredentialStore(this)
        controller = TvProfilesController(
            surface = this,
            gateway = RepositoryTvProfilesGateway(
                repository = ProfilesRepository(),
                baseUrl = ru.mars.network.api.MarsApi.BASE_URL,
                credentialProvider = { store.read() },
            ),
        )
        setContentView(R.layout.activity_tv_profiles)
        statusView = findViewById(R.id.statusView)
        loadingGroup = findViewById(R.id.loadingGroup)
        errorGroup = findViewById(R.id.errorGroup)
        errorText = findViewById(R.id.errorText)
        dataScroll = findViewById(R.id.dataScroll)
        emptyText = findViewById(R.id.emptyText)
        profilesContainer = findViewById(R.id.profilesContainer)

        val back = findViewById<Button>(R.id.backButton)
        back.setOnClickListener { finish() }
        val refresh = findViewById<Button>(R.id.refreshButton)
        refresh.setOnClickListener { reload() }
        val retry = findViewById<Button>(R.id.retryButton)
        retry.setOnClickListener { reload() }
        TvFocusLift.attachAll(reducedMotion, back, refresh, retry)

        selectedIndex = savedInstanceState?.getInt(STATE_SELECTED, TvProfilesPolicy.NO_SELECTION)
            ?: TvProfilesPolicy.NO_SELECTION
        back.requestFocus()
        reload()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED, selectedIndex)
    }

    override fun onDestroy() {
        controller.destroy()
        super.onDestroy()
    }

    private fun reload() {
        controller.load()
    }

    // ---- TvProfilesSurface ---------------------------------------------------

    override fun showLoading() {
        // Список уже на экране — не мигаем: остаётся прошлый каталог, плашку
        // обновит следующий showProfiles/showError.
        if (dataScroll.visibility == View.VISIBLE) return
        loadingGroup.visibility = View.VISIBLE
        errorGroup.visibility = View.GONE
        dataScroll.visibility = View.GONE
        statusView.text = LOADING
    }

    override fun showProfiles(response: ProfilesResponse, status: TvOfflineCacheStatus, badgeText: String?) {
        loadingGroup.visibility = View.GONE
        errorGroup.visibility = View.GONE
        dataScroll.visibility = View.VISIBLE
        statusView.text = TvProfilesPolicy.statusLine(TvProfilesPolicy.summaryLine(response), badgeText)
        renderRows(TvProfilesPolicy.rows(response))
        val empty = TvProfilesPolicy.emptyLine(response)
        emptyText.text = empty.orEmpty()
        emptyText.visibility = if (empty == null) View.GONE else View.VISIBLE
    }

    override fun showError(message: String) {
        loadingGroup.visibility = View.GONE
        dataScroll.visibility = View.GONE
        errorGroup.visibility = View.VISIBLE
        errorText.text = message
        statusView.text = message
        selectedIndex = TvProfilesPolicy.NO_SELECTION
        findViewById<Button>(R.id.retryButton).requestFocus()
    }

    // ---- Render --------------------------------------------------------------

    /**
     * Перерисовка каталога с восстановлением фокуса по индексу: карточки
     * пересобираются целиком (каталог небольшой), но пульт остаётся на той же
     * позиции — [TvProfilesPolicy.restoreSelection] держит края без переноса.
     */
    private fun renderRows(rows: List<TvProfilesPolicy.ProfileRow>) {
        val hadFocus = profilesContainer.findFocus() != null
        profilesContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        rows.forEach { row ->
            val card = inflater.inflate(R.layout.item_tv_profile, profilesContainer, false)
            bindCard(card, row)
            profilesContainer.addView(card)
        }
        selectedIndex = TvProfilesPolicy.restoreSelection(selectedIndex, rows.size)
        if (!hadFocus && rows.isNotEmpty()) {
            profilesContainer.getChildAt(selectedIndex)?.requestFocus()
        }
    }

    private fun bindCard(card: View, row: TvProfilesPolicy.ProfileRow) {
        card.findViewById<TextView>(R.id.profileTitle).text = row.title
        card.findViewById<TextView>(R.id.profileMeta).text = TvProfilesPolicy.metaLine(row)
        val path = card.findViewById<TextView>(R.id.profilePath)
        path.text = row.path.orEmpty()
        path.visibility = if (row.path == null) View.GONE else View.VISIBLE
        card.findViewById<TextView>(R.id.profileActiveBadge).visibility =
            if (row.active) View.VISIBLE else View.GONE
        card.findViewById<TextView>(R.id.profileDefaultBadge).visibility =
            if (row.default) View.VISIBLE else View.GONE
        card.findViewById<TextView>(R.id.profileHiddenBadge).visibility =
            if (row.hidden) View.VISIBLE else View.GONE
        card.contentDescription = TvProfilesPolicy.cardDescription(row)
        // OK на пульте = клик по карточке: переход в чат профиля (read-only,
        // скрытый профиль чата не имеет — карточка остаётся только для просмотра).
        val chatProfile = TvProfilesPolicy.chatExtra(row)
        if (chatProfile == null) {
            card.isClickable = false
            card.setOnClickListener(null)
        } else {
            card.isClickable = true
            card.setOnClickListener { openChat(chatProfile) }
        }
        card.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) selectedIndex = profilesContainer.indexOfChild(card)
        }
        TvFocusLift.attach(card, reducedMotion)
    }

    /** Чат профиля: имя уезжает extra'ом, переключения профиля по дороге нет. */
    private fun openChat(profile: String) {
        val intent = Intent(this, TvChatActivity::class.java)
        TvProfilesRouting.profileExtra(profile)?.let { intent.putExtra(TvProfilesRouting.EXTRA_PROFILE, it) }
        startActivity(intent)
    }

    private companion object {
        const val LOADING = "Загрузка профилей…"
        const val STATE_SELECTED = "tv_profiles_selected"
    }
}
