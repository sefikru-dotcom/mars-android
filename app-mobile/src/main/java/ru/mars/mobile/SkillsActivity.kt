package ru.mars.mobile

import android.app.Dialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.mars.network.api.MarsApi
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.SkillItem
import ru.mars.network.repository.SkillsRepository

/**
 * Экран «Скиллы Марса»: каталог `GET /api/skills` с поиском и фильтром по категориям,
 * содержимое скилла — `GET /api/skills/content?name=...` в модалке [MarsDialog].
 * Фильтрация/сортировка/чипсы — чистый [SkillsFilterPolicy], сеть — [SkillsRepository].
 */
class SkillsActivity : ComponentActivity() {
    private lateinit var store: AndroidKeyStoreCredentialStore
    private val repository = SkillsRepository()

    private lateinit var searchInput: EditText
    private lateinit var chipsRow: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var progress: ProgressBar

    private var skills: List<SkillItem> = emptyList()
    private var selectedCategory = SkillsFilterPolicy.ALL
    private var loaded = false
    private var loading = false
    private var failure: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AndroidKeyStoreCredentialStore(this)
        setContentView(buildView())
        load()
    }

    private fun buildView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MarsTheme.BG)
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16), systemBars.top + dp(10), dp(16), systemBars.bottom + dp(10))
            insets
        }

        // Header: back / title / progress / refresh
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
            text = "Скиллы Марса"
            textSize = 22f
            setTextColor(MarsTheme.TEXT_STRONG)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        progress = ProgressBar(this).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        header.addView(progress, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(8) })
        header.addView(
            MarsTheme.circleIconButton(this, R.drawable.ic_refresh, "Обновить", bg = MarsTheme.SURFACE, iconTint = MarsTheme.TEXT)
                .apply { setOnClickListener { load() } },
            LinearLayout.LayoutParams(dp(40), dp(40))
        )
        root.addView(header)

        // Search box: pill with a leading icon and a borderless field inside.
        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = MarsTheme.pill(this@SkillsActivity, MarsTheme.SURFACE, MarsTheme.BORDER)
            setPadding(dp(14), 0, dp(14), 0)
        }
        searchBox.addView(ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@SkillsActivity, R.drawable.ic_search))
            imageTintList = android.content.res.ColorStateList.valueOf(MarsTheme.TEXT_MUTED)
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
        searchInput = EditText(this).apply {
            hint = "Поиск скилла…"
            setHintTextColor(MarsTheme.TEXT_MUTED)
            setTextColor(MarsTheme.TEXT)
            textSize = 14f
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            doAfterTextChanged { renderList() }
        }
        searchBox.addView(searchInput, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(searchBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // Category chips (rebuilt when the catalog loads / category changes).
        chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipsRow, ViewGroup.LayoutParams(-2, -2))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(list, ViewGroup.LayoutParams(-1, -2))
            },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        return root
    }

    private fun load() {
        if (loading || isFinishing) return
        val credential = store.read()
        if (credential == null) {
            failure = "Нужен вход: откройте приложение и пройдите пару"
            loaded = false
            renderChips()
            renderList()
            return
        }
        loading = true
        failure = null
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = repository.getSkills(MarsApi.BASE_URL, credential)
            loading = false
            progress.visibility = View.GONE
            if (result.isEmpty()) {
                failure = "Каталог скиллов пуст или недоступен"
                skills = emptyList()
                loaded = false
            } else {
                skills = result
                loaded = true
                failure = null
                if (selectedCategory != SkillsFilterPolicy.ALL && selectedCategory !in SkillsFilterPolicy.categories(result)) {
                    selectedCategory = SkillsFilterPolicy.ALL
                }
            }
            renderChips()
            renderList()
        }
    }

    private fun renderChips() {
        chipsRow.removeAllViews()
        if (!loaded) return
        SkillsFilterPolicy.categories(skills).forEachIndexed { index, category ->
            val selected = category == selectedCategory
            val chip = MarsDialog.pill(
                this,
                SkillsFilterPolicy.categoryLabel(category),
                bg = if (selected) MarsTheme.ACCENT_BG else MarsTheme.SURFACE_SUBTLE,
                fg = if (selected) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_MUTED,
                border = if (selected) MarsTheme.ACCENT_BORDER else MarsTheme.BORDER_MUTED,
            ) {
                if (selectedCategory != category) {
                    selectedCategory = category
                    renderChips()
                    renderList()
                }
            }
            chipsRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
    }

    private fun renderList() {
        list.removeAllViews()
        if (!loaded) {
            if (failure != null) statusText(failure!!, MarsTheme.STATUS_WARNING)
            else if (!loading) statusText("Загрузка каталога…", MarsTheme.TEXT_MUTED)
            return
        }
        val visible = SkillsFilterPolicy.sorted(
            SkillsFilterPolicy.filter(skills, searchInput.text.toString(), selectedCategory)
        )
        if (visible.isEmpty()) {
            statusText("Ничего не найдено", MarsTheme.TEXT_MUTED)
            return
        }
        visible.forEach { skill -> list.addView(skillCard(skill), cardMargin()) }
    }

    private fun statusText(message: String, color: Int) {
        list.addView(TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(color)
            setPadding(0, dp(24), 0, 0)
            gravity = Gravity.CENTER_HORIZONTAL
        })
    }

    private fun skillCard(skill: SkillItem): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = MarsTheme.card(this@SkillsActivity, MarsTheme.SURFACE, radiusDp = 14)
            isClickable = true
            isFocusable = true
            setOnClickListener { openSkill(skill) }
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply {
            text = skill.name
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(MarsTheme.TEXT_STRONG)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (skill.category.isNotBlank()) {
            row.addView(
                MarsDialog.pill(
                    this,
                    SkillsFilterPolicy.categoryLabel(skill.category),
                    bg = MarsTheme.ACCENT_BG,
                    fg = MarsTheme.ACCENT_TEXT,
                    border = MarsTheme.ACCENT_BORDER,
                ).apply {
                    textSize = 11f
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                },
                LinearLayout.LayoutParams(-2, -2)
            )
        }
        card.addView(row)
        if (skill.description.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = skill.description
                textSize = 13f
                setTextColor(MarsTheme.TEXT_MUTED)
                setLineSpacing(dp(2).toFloat(), 1f)
                maxLines = 3
                setPadding(0, dp(6), 0, 0)
            })
        }
        return card
    }

    /** Содержимое SKILL.md в модалке: monospace, выделяемый, внутри общего ScrollView. */
    private fun openSkill(skill: SkillItem) {
        val credential = store.read() ?: return
        lifecycleScope.launch {
            val detail = repository.getSkillContent(MarsApi.BASE_URL, credential, skill.name)
            var dialog: Dialog? = null
            dialog = MarsDialog.show(this@SkillsActivity) { root ->
                root.addView(MarsDialog.header(this@SkillsActivity, skill.name, skill.category.takeIf(String::isNotBlank)))
                val text = detail?.content?.takeIf(String::isNotBlank)
                if (text == null) {
                    root.addView(MarsDialog.paragraph(this@SkillsActivity, "Не удалось загрузить содержимое скилла."))
                } else {
                    root.addView(TextView(this@SkillsActivity).apply {
                        this.text = text
                        setTextColor(MarsTheme.TEXT_EM)
                        typeface = Typeface.MONOSPACE
                        textSize = 12f
                        setTextIsSelectable(true)
                        setLineSpacing(dp(2).toFloat(), 1f)
                    })
                }
                root.addView(MarsDialog.quietButton(this@SkillsActivity, "Закрыть") { dialog?.dismiss() })
                emptyList()
            }
        }
    }

    private fun cardMargin() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    private fun dp(v: Int) = MarsTheme.dp(this, v)
}
