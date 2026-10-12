package ru.mars.tv.skills

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ru.mars.network.api.MarsApi
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.tv.R
import ru.mars.tv.TvFocusLift
import ru.mars.tv.TvOfflineCacheStatus

/**
 * TV-экран «Скиллы» (read-only паритет мобильного SkillsActivity): каталог
 * скиллов сервера — GET /api/skills, чипы категорий, сортировка по имени или
 * по использованиям, бейдж «использован N раз»; автообновление раз в 60 с.
 *
 * СТРОГО READ-ONLY: /api/skills входит в белый список нативного device-токена
 * (hermes-webui `NATIVE_BEARER_EXACT_PATHS`), мутация /api/skills/save токеном
 * не принимается — экран показывает каталог, но не вызывает и не правит скиллы.
 * Статистика /api/skills/usage вне белого списка — [HttpTvSkillsGateway]
 * запрашивает её fail-soft: без статистики экран честно показывает каталог
 * без счётчиков.
 *
 * Состояния экрана: loading / error / data. Пустой каталог — валидное состояние
 * ([TvSkillsPolicy.EMPTY_LIST]), пустая выдача фильтра — [TvSkillsPolicy.FILTER_EMPTY];
 * ни то ни другое не ошибка. [R.id.statusView] — accessibility live region
 * (polite). D-pad: «Назад»/сортировка/«Обновить» в шапке, чипы категорий и
 * карточки фокусируемые ([TvFocusLift]); в ошибке фокус на «Повторить».
 *
 * Data/policy слои отделены от UI: [HttpTvSkillsGateway] (транспорт),
 * [TvSkillsPolicy] (чистые решения), [TvSkillsController] (оркестрация,
 * generation guard, кэш последнего удачного ответа, destroy) — JVM-тесты.
 *
 * Offline: при сетевом сбое экран рисует последний удачный снимок со статусом
 * [TvOfflineCacheStatus.CACHED] и плашкой возраста вместо ошибки; явная ошибка —
 * только когда показывать нечего (кэш пуст или старше 24 ч).
 */
class TvSkillsActivity : AppCompatActivity() {

    private lateinit var store: AndroidKeyStoreCredentialStore
    private lateinit var statusView: TextView
    private lateinit var loadingGroup: View
    private lateinit var errorGroup: View
    private lateinit var errorText: TextView
    private lateinit var retryButton: Button
    private lateinit var backButton: Button
    private lateinit var sortButton: Button
    private lateinit var dataScroll: View
    private lateinit var emptyText: TextView
    private lateinit var chipsRow: LinearLayout
    private lateinit var skillsContainer: LinearLayout
    private lateinit var controller: TvSkillsController

    /** Id категорий текущих чипов (параллельно детям [chipsRow]); пересборка только при изменении. */
    private var chipCategories: List<String> = emptyList()
    private var selectedCategory = TvSkillsPolicy.ALL
    private var sortMode = TvSkillsSort.NAME

    private val handler = Handler(Looper.getMainLooper())
    private var hasData = false
    private var currentData: TvSkillsData? = null

    /** Плашка источника данных из контроллера (LIVE/CACHED); null — данные ещё не показывались. */
    private var currentBadge: String? = null

    private val refreshTick = object : Runnable {
        override fun run() {
            controller.load()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private val surface = object : TvSkillsSurface {
        override fun showLoading() {
            if (hasData) {
                // Фоновое обновление: каталог остаётся на экране, live region объявит статус.
                statusView.text = "Обновление…"
                return
            }
            loadingGroup.visibility = View.VISIBLE
            errorGroup.visibility = View.GONE
            dataScroll.visibility = View.GONE
            statusView.text = "Загрузка каталога…"
        }

        override fun showSkills(data: TvSkillsData, status: TvOfflineCacheStatus, badgeText: String?) {
            // CACHED рисуется как обычные данные: видимость та же, фокус не трогаем.
            hasData = true
            currentData = data
            currentBadge = badgeText
            loadingGroup.visibility = View.GONE
            errorGroup.visibility = View.GONE
            dataScroll.visibility = View.VISIBLE
            render()
        }

        override fun showError(message: String) {
            statusView.text = message
            if (hasData) {
                // Последний каталог остаётся на экране; сбой объявлен live region'ом.
                return
            }
            loadingGroup.visibility = View.GONE
            errorGroup.visibility = View.VISIBLE
            dataScroll.visibility = View.GONE
            errorText.text = message
            backButton.nextFocusDownId = R.id.retryButton
            retryButton.requestFocus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_skills)
        store = AndroidKeyStoreCredentialStore(this)

        backButton = findViewById(R.id.backButton)
        sortButton = findViewById(R.id.sortButton)
        statusView = findViewById(R.id.statusView)
        loadingGroup = findViewById(R.id.loadingGroup)
        errorGroup = findViewById(R.id.errorGroup)
        errorText = findViewById(R.id.errorText)
        retryButton = findViewById(R.id.retryButton)
        dataScroll = findViewById(R.id.dataScroll)
        emptyText = findViewById(R.id.emptyText)
        chipsRow = findViewById(R.id.chipsRow)
        skillsContainer = findViewById(R.id.skillsContainer)
        val refreshButton = findViewById<Button>(R.id.refreshButton)
        findViewById<TextView>(R.id.noticeText).text =
            "Каталог только для чтения — вызов скилла доступен в мобильном клиенте и чате"

        sortButton.text = TvSkillsPolicy.sortLabel(sortMode)
        sortButton.setOnClickListener { toggleSort() }

        backButton.setOnClickListener { finish() }
        refreshButton.setOnClickListener { controller.load() }
        retryButton.setOnClickListener { controller.load() }

        controller = TvSkillsController(
            surface,
            HttpTvSkillsGateway(MarsApi.BASE_URL, credentialProvider = { store.read() }),
        )
        TvFocusLift.attachAll(backButton, sortButton, refreshButton, retryButton)
        backButton.requestFocus()

        controller.load()
        handler.postDelayed(refreshTick, REFRESH_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshTick)
        if (::controller.isInitialized) controller.destroy()
        super.onDestroy()
    }

    private fun toggleSort() {
        sortMode = when (sortMode) {
            TvSkillsSort.NAME -> TvSkillsSort.USAGE
            TvSkillsSort.USAGE -> TvSkillsSort.NAME
        }
        sortButton.text = TvSkillsPolicy.sortLabel(sortMode)
        render()
    }

    private fun selectCategory(category: String) {
        if (category == selectedCategory) return
        selectedCategory = category
        render()
    }

    private fun render() {
        val data = currentData ?: return
        renderChips(data)
        // Автообновление/фильтр пересобирают список: запоминаем позицию D-pad фокуса и
        // возвращаем её (clamp по [TvSkillsPolicy.restoreSelection], без wrap).
        val focusedIndex = skillsContainer.indexOfChild(skillsContainer.findFocus())
        skillsContainer.removeAllViews()

        val rows = TvSkillsPolicy.rows(data.skills, data.usage, selectedCategory, sortMode)

        // Live-регион: плашка источника + счётчик + (когда есть) статистика вызовов.
        val parts = buildList {
            currentBadge?.let { add(it) }
            add(TvSkillsPolicy.summaryLine(rows))
            TvSkillsPolicy.usageSummaryLine(data.usage)?.let { add(it) }
        }
        statusView.text = parts.joinToString(" · ")

        val emptyLine = TvSkillsPolicy.emptyLine(data.skills, rows)
        if (emptyLine != null) {
            emptyText.text = emptyLine
            emptyText.visibility = View.VISIBLE
        } else {
            emptyText.visibility = View.GONE
        }

        rows.forEach { row -> skillsContainer.addView(skillRow(row)) }

        if (focusedIndex >= 0) {
            val target = TvSkillsPolicy.restoreSelection(focusedIndex, skillsContainer.childCount)
            if (target != TvSkillsPolicy.NO_SELECTION) {
                skillsContainer.getChildAt(target)?.requestFocus()
            }
        }
    }

    /** Чипы категорий: пересборка только когда набор категорий изменился (фокус не сбрасывается). */
    private fun renderChips(data: TvSkillsData) {
        val categories = TvSkillsPolicy.categories(data.skills)
        if (categories != chipCategories) {
            if (selectedCategory !in categories) selectedCategory = TvSkillsPolicy.ALL
            chipCategories = categories
            chipsRow.removeAllViews()
            categories.forEach { category ->
                val chip = chip(TvSkillsPolicy.categoryLabel(category))
                chip.tag = category
                chip.setOnClickListener { selectCategory(category) }
                chipsRow.addView(chip)
                TvFocusLift.attach(chip)
            }
        }
        for (i in 0 until chipsRow.childCount) {
            val chip = chipsRow.getChildAt(i)
            chip.isSelected = chip.tag == selectedCategory
        }
    }

    /** Программный чип — визуальный паритет чипов ряда фильтров «Аналитики». */
    private fun chip(label: String): Button {
        val button = Button(this)
        button.text = label
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        button.setTextColor(ContextCompat.getColor(this, R.color.mars_text))
        button.setBackgroundResource(R.drawable.bg_focus_chip)
        button.minWidth = 0
        button.minimumWidth = 0
        button.minHeight = 0
        button.minimumHeight = 0
        button.isAllCaps = false
        val padH = dpToPx(24)
        val padV = dpToPx(10)
        button.setPadding(padH, padV, padH, padV)
        button.isFocusable = true
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.marginEnd = dpToPx(12)
        button.layoutParams = params
        return button
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun skillRow(row: TvSkillsPolicy.SkillRow): View {
        val view = layoutInflater.inflate(R.layout.item_tv_skill, skillsContainer, false)
        view.findViewById<TextView>(R.id.skillName).text = row.name
        val badge = view.findViewById<TextView>(R.id.skillBadge)
        badge.text = row.usageBadge.orEmpty()
        badge.visibility = if (row.usageBadge != null) View.VISIBLE else View.GONE
        val category = view.findViewById<TextView>(R.id.skillCategory)
        category.text = row.category
        category.visibility = if (row.category.isNotEmpty()) View.VISIBLE else View.GONE
        val description = view.findViewById<TextView>(R.id.skillDescription)
        description.text = row.description
        description.visibility = if (row.description.isNotEmpty()) View.VISIBLE else View.GONE
        TvFocusLift.attach(view)
        return view
    }

    companion object {
        /** Интервал автообновления: каталог меняется редко — минута достаточна. */
        private const val REFRESH_MS = 60_000L
    }
}
