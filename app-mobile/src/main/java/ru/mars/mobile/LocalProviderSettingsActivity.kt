package ru.mars.mobile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.local.LocalChatClient
import ru.mars.network.local.LocalModelInfo
import ru.mars.network.local.LocalProviderProfile
import ru.mars.network.local.LocalProviderStore
import ru.mars.network.local.LocalProviderUrls
import java.util.Locale
import java.util.UUID

/**
 * Настройки прямого провайдера: профили, адрес, модель, reasoning, параметры хода, ключ.
 *
 * Профили и открытые параметры живут в [LocalProviderPrefsStore] (SharedPreferences),
 * ключ — только в AndroidKeyStore через `saveApiKey`. Поэтому:
 *  - сохранённый ключ на экране показывается как `••••` (подсказка поля), а не текстом;
 *  - ключ не логируется и после сохранения поле очищается;
 *  - «Проверить» зовёт `LocalChatClient.catalog()` — это единственная проверка адреса,
 *    ключа и размера окна контекста, которую можно сделать до первого сообщения.
 *
 * Модель выбирается ИЗ КАТАЛОГА, а не вбивается текстом: у провайдера 85 моделей с
 * разными окнами контекста, и человек, набравший имя по памяти, узнаёт об опечатке
 * только из 400-й ошибки. Диалог выбора показывает поиск, окно контекста и метку
 * `reasoning` — по ней видно, у каких моделей есть переключатель уровня размышлений.
 *
 * Reasoning-селектор показывается ТОЛЬКО когда выбранная модель сообщила
 * `reasoning_controls` (`LocalModelInfo.supportsReasoningEffort`): у 14 моделей из 85
 * его нет, и всегда видимая кнопка была бы мёртвой.
 *
 * Транспорт берётся из [LocalChatTransport]: пока реализация клиента не собрана,
 * проверка честно отвечает «транспорт недоступен», а не молчит.
 */
class LocalProviderSettingsActivity : ComponentActivity() {

    companion object {
        fun newIntent(context: Context): Intent =
            Intent(context, LocalProviderSettingsActivity::class.java)

        /** Диапазон temperature, который принимают [OI]-совместимые провайдеры. */
        private const val MIN_TEMPERATURE = 0.0
        private const val MAX_TEMPERATURE = 2.0

        /** Разумный предел ответа: больше не отдаёт ни один провайдер каталога. */
        private const val MIN_MAX_TOKENS = 1
        private const val MAX_MAX_TOKENS = 200_000

        /** «Авто» = поле `reasoning_effort` не отправляется. */
        private const val REASONING_AUTO = "Авто"

        /** Уровни, которые принимает провайдер (проверено живым запросом, §2.4). */
        private val REASONING_LEVELS = listOf("low", "medium", "high")
    }

    private lateinit var store: LocalProviderStore
    private lateinit var client: LocalChatClient

    private lateinit var scaffold: SectionScaffold.Parts
    private lateinit var profilesBox: LinearLayout
    private lateinit var titleField: EditText
    private lateinit var baseUrlField: EditText
    private lateinit var keyField: EditText
    private lateinit var temperatureField: EditText
    private lateinit var maxTokensField: EditText
    private lateinit var modelButton: TextView
    private lateinit var modelHint: TextView
    private lateinit var reasoningBlock: LinearLayout
    private lateinit var reasoningButton: TextView
    private lateinit var status: TextView

    /** Ключ и модель картинок Yeta: отдельное хранилище, отдельный алиас Keystore. */
    private lateinit var imageKeys: YetaImageKeyStore
    private lateinit var imageKeyField: EditText
    private lateinit var imageModelButton: TextView
    private lateinit var imageKeyStatus: TextView

    /** Профиль, который сейчас редактируется; `null` — новый. */
    private var editingId: String? = null

    /**
     * Форма заполняется с нуля по кнопке «Новый профиль». Флаг нужен, чтобы
     * автозаполнение при перерисовке списка не подставляло активный профиль поверх
     * пустой формы (иначе «Новый профиль» мгновенно превращался в правку старого).
     */
    private var draftingNew = false

    /** Сохранён ли ключ у редактируемого профиля (для подсказки `••••`). */
    private var keySaved = false

    /** Выбранная модель и её метаданные: живут отдельно от текста полей. */
    private var selectedModel: String = LocalProviderDefaults.MODEL

    /**
     * Модель, подставленная в форму при последнем [fillForm].
     *
     * Нужна, чтобы отличить «человек выбрал модель здесь» от «поле просто держит старую
     * копию»: при возврате на экран сохранённая модель подтягивается только во втором
     * случае (см. [onResume]).
     */
    private var modelAtFill: String = LocalProviderDefaults.MODEL
    private var selectedContextWindow: Int? = null
    private var selectedSupportsReasoning = false
    private var selectedReasoningEffort: String? = null

    /** Последний удачный каталог: офлайн диалог выбора показывает его, а не пустоту. */
    private var catalogCache: List<LocalModelInfo> = emptyList()
    private var catalogBaseUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LocalProviderPrefsStore(this)
        imageKeys = YetaImageKeyStore(this)
        client = LocalChatTransport.clientOrUnavailable()
        setContentView(buildView())
        reload()
    }

    private fun buildView(): View {
        scaffold = SectionScaffold.create(this, "Провайдер", ::reload)

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scaffold.content.addView(
            ScrollView(this).apply {
                isFillViewport = true
                isHorizontalScrollBarEnabled = false
                addView(column, ViewGroup.LayoutParams(-1, -2))
            },
            LinearLayout.LayoutParams(-1, 0, 1f),
        )

        column.addView(sectionHeader("Профили", ::newProfile))

        profilesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(profilesBox, LinearLayout.LayoutParams(-1, -2))

        column.addView(sectionHeader("Параметры", null), LinearLayout.LayoutParams(-1, -2))

        titleField = field("Название (необязательно)")
        baseUrlField = field("https://plusvibeapi.ru/v1", InputType.TYPE_TEXT_VARIATION_URI)
        keyField = field("Ключ API", InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            // Ключ не должен показываться на экране даже при наборе.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        temperatureField = numberField("Необязательно · 0–2", decimal = true)
        maxTokensField = numberField("Необязательно · 1–200000", decimal = false)
        // Разрешённые значения по умолчанию — В ПОЛЯХ, а не только подсказкой:
        // марс использует один провайдер, и каждый раз вбивать адрес с моделью заново
        // бессмысленно. Подсказка (hint) исчезает при вводе и не читается кодом, из-за
        // чего «Проверить» отвечала «Укажите адрес провайдера» на заполненном с виду
        // экране (поймано на POCO F5). Заводские значения приходят из
        // [LocalProviderDefaults]; уже сохранённый профиль их перекрывает.
        baseUrlField.setText(LocalProviderDefaults.BASE_URL)

        column.addView(fieldBlock("Название", titleField))
        column.addView(fieldBlock("Адрес (baseUrl)", baseUrlField))
        column.addView(modelBlock())
        column.addView(reasoningRow())
        column.addView(fieldBlock("Temperature", temperatureField))
        column.addView(fieldBlock("Max tokens (ответ)", maxTokensField))
        column.addView(fieldBlock("Ключ API", keyField))

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(MarsTheme.TEXT_MUTED)
            visibility = View.GONE
            // Результат проверки озвучивается TalkBack при смене текста.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(dp(2), dp(10), dp(2), dp(2))
        }
        column.addView(status, LinearLayout.LayoutParams(-1, -2))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(6))
        }
        actions.addView(actionPill("Проверить", accent = false) { verify() })
        actions.addView(
            actionPill("Сохранить", accent = true) { save() },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) },
        )
        column.addView(actions, LinearLayout.LayoutParams(-1, -2))

        column.addView(imageKeySection(), LinearLayout.LayoutParams(-1, -2))

        return scaffold.root
    }

    // --- ключ Yeta для картинок -----------------------------------------------------------

    /**
     * Блок «Картинки»: ключ Yeta и модель генерации.
     *
     * Почему отдельный ключ, а не поле профиля: генерация картинок — отдельная задача
     * (§3 контракта), и чат может идти через PlusVibe, пока картинки рисует Yeta. Ключ
     * шифруется отдельным алиасом AndroidKeyStore (`YetaImageKeyStore`), поэтому
     * удаление профиля провайдера его не трогает, и наоборот.
     *
     * Поле пустое и показывает `••••` подсказкой — как у ключа профиля: сохранённый
     * секрет на экране не выводится никогда. «Сохранить ключ» пишет только этот ключ,
     * профиль не трогая: иначе человек, зашедший за картинками, случайно переписал бы
     * адрес и модель чата тем, что видит в форме.
     */
    private fun imageKeySection(): View {
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        block.addView(sectionHeader("Картинки (Yeta)", null))

        block.addView(
            TextView(this).apply {
                text = "Модель рисует картинки по просьбе в чате (инструмент generate_image). " +
                    "Ключ хранится отдельно от ключа провайдера и шифруется в AndroidKeyStore."
                textSize = 11.5f
                setTextColor(MarsTheme.TEXT_MUTED)
                setLineSpacing(0f, 1.15f)
            },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) },
        )

        imageKeyField = field("Ключ Yeta API", InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = if (imageKeys.hasKey()) "Ключ сохранён ••••" else "Ключ Yeta API"
        }
        block.addView(fieldBlock("Ключ Yeta (картинки)", imageKeyField))

        imageModelButton = TextView(this).apply {
            textSize = 14f
            setTextColor(MarsTheme.TEXT)
            background = MarsTheme.button(
                this@LocalProviderSettingsActivity,
                MarsTheme.CODE_BG,
                radiusDp = 12,
                strokeColor = MarsTheme.BORDER_MUTED,
            )
            setPadding(dp(14), dp(12), dp(14), dp(12))
            maxLines = 1
            isClickable = true
            isFocusable = true
            compoundDrawablePadding = dp(8)
            setOnClickListener { chooseImageModel() }
        }
        updateImageModelUi()
        block.addView(fieldBlock("Модель картинок", imageModelButton))

        imageKeyStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(MarsTheme.TEXT_MUTED)
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(dp(2), dp(8), dp(2), dp(2))
        }
        block.addView(imageKeyStatus, LinearLayout.LayoutParams(-1, -2))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        actions.addView(actionPill("Сохранить ключ", accent = true) { saveImageKey() })
        actions.addView(
            actionPill("Забыть ключ", accent = false) { forgetImageKey() },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) },
        )
        block.addView(actions, LinearLayout.LayoutParams(-1, -2))
        return block
    }

    private fun updateImageModelUi() {
        if (!::imageModelButton.isInitialized) return
        val model = imageKeys.model()
        imageModelButton.text = model
        imageModelButton.contentDescription = "Модель картинок: $model. Нажмите, чтобы изменить"
    }

    /** Ввод ключа Yeta: пустое поле при сохранённом ключе не стирает его — это отдельная кнопка. */
    private fun saveImageKey() {
        val typed = imageKeyField.text.toString().trim()
        if (typed.isEmpty() && !imageKeys.hasKey()) {
            setImageStatus("Введите ключ Yeta: без него картинки не генерируются", true)
            return
        }
        runCatching {
            if (typed.isNotEmpty()) imageKeys.saveApiKey(typed)
            imageKeys.saveModel(imageKeys.model())
        }.onFailure { error ->
            setImageStatus("Ключ не сохранился: ${error.message ?: "ошибка"}", true)
            return
        }
        // Поле очищается: секрет не должен оставаться ни в UI, ни в состоянии View.
        imageKeyField.setText("")
        imageKeyField.hint = if (imageKeys.hasKey()) "Ключ сохранён ••••" else "Ключ Yeta API"
        setImageStatus(
            "Ключ Yeta сохранён · модель ${imageKeys.model()}. Проверить можно в чате: «нарисуй кота».",
            false,
        )
    }

    private fun forgetImageKey() {
        if (!imageKeys.hasKey()) {
            setImageStatus("Ключа Yeta нет — стирать нечего", false)
            return
        }
        lifecycleScope.launch {
            val allowed = LocalChatConfirmDialog.ask(
                activity = this@LocalProviderSettingsActivity,
                title = "Забыть ключ Yeta?",
                body = "Генерация картинок перестанет работать, пока ключ не введут заново.",
                confirmLabel = "Забыть",
                denyLabel = "Отмена",
            )
            if (!allowed) return@launch
            runCatching { imageKeys.clearApiKey() }
                .onFailure { error ->
                    setImageStatus("Не удалилось: ${error.message ?: "ошибка"}", true)
                    return@launch
                }
            imageKeyField.hint = "Ключ Yeta API"
            setImageStatus("Ключ Yeta удалён", false)
        }
    }

    private fun chooseImageModel() {
        var dialog: android.app.Dialog? = null
        val current = imageKeys.model()
        dialog = MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, "Модель картинок", YetaImageKeyStore.MODELS.size.toString()))
            root.addView(
                MarsDialog.paragraph(
                    this,
                    "seedream-5.0-flash — быстрая, по умолчанию. seedream-5.0-pro — лучше в деталях. " +
                        "seededit-3.0 — правка присланного фото.",
                )
            )
            root.addView(
                MarsDialog.pillStack(
                    this,
                    YetaImageKeyStore.MODELS.map { model ->
                        val label = if (model == current) "$model · выбрано" else model
                        label to {
                            runCatching { imageKeys.saveModel(model) }
                            updateImageModelUi()
                            setImageStatus("Модель картинок: $model", false)
                            dialog?.dismiss()
                        }
                    },
                )
            )
            root.addView(
                MarsDialog.quietButton(this, "Закрыть") { dialog?.dismiss() },
                LinearLayout.LayoutParams(-2, -2),
            )
            emptyList()
        }
    }

    private fun setImageStatus(text: String, error: Boolean) {
        imageKeyStatus.text = text
        imageKeyStatus.setTextColor(if (error) MarsTheme.STATUS_WARNING else MarsTheme.TEXT_MUTED)
        imageKeyStatus.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun reload() {
        scaffold.progress.visibility = View.GONE
        renderProfiles()
    }

    // --- профили -------------------------------------------------------------------------

    private fun renderProfiles() {
        val state = runCatching { store.state() }.getOrNull()
        val profiles = state?.profiles.orEmpty()
        profilesBox.removeAllViews()

        if (profiles.isEmpty()) {
            profilesBox.addView(
                SectionStateViews.empty(
                    this,
                    "Профилей нет. Выберите модель, заполните адрес и ключ ниже и нажмите «Сохранить».",
                )
            )
            if (editingId != null) {
                editingId = null
                keySaved = false
                applyKeyHint()
            }
            updateModelUi()
            return
        }

        profiles.forEach { profile ->
            profilesBox.addView(
                profileRow(profile, isActive = profile.id == state?.activeId),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) },
            )
        }

        if (editingId == null && !draftingNew) {
            // Первое открытие: показываем активный профиль, иначе первый в списке.
            fillForm(state?.active ?: profiles.first())
        }
    }

    private fun profileRow(profile: LocalProviderProfile, isActive: Boolean): View {
        val selected = profile.id == editingId
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(8), dp(10))
            background = MarsTheme.button(
                this@LocalProviderSettingsActivity,
                if (selected) MarsTheme.ACCENT_BG else MarsTheme.SURFACE_CARD,
                radiusDp = 12,
                strokeColor = if (selected) MarsTheme.ACCENT_BORDER else MarsTheme.BORDER,
            )
            isClickable = true
            isFocusable = true
            contentDescription = "${profile.title}, ${profile.model}" +
                if (isActive) ", активный" else ""
            setOnClickListener {
                draftingNew = false
                editingId = profile.id
                fillForm(profile)
                renderProfiles()
            }
        }

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = profile.title
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(MarsTheme.TEXT_STRONG)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val window = profile.contextWindow
        val subtitle = buildString {
            append(profile.baseUrl).append(" · ").append(profile.model)
            if (window != null) append(" · окно ").append(compactTokens(window.toLong()))
            profile.reasoningEffort?.let { append(" · reasoning ").append(it) }
        }
        texts.addView(TextView(this).apply {
            text = subtitle
            textSize = 11.5f
            setTextColor(MarsTheme.TEXT_MUTED)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))

        if (isActive) {
            row.addView(TextView(this).apply {
                text = "активен"
                textSize = 10f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(MarsTheme.ACCENT_TEXT)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = MarsTheme.pill(this@LocalProviderSettingsActivity, MarsTheme.ACCENT_BG, MarsTheme.ACCENT_BORDER)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        } else {
            // Переключение активного профиля: без этого второй профиль бесполезен —
            // его нельзя выбрать тем, которым отвечает чат.
            row.addView(TextView(this).apply {
                text = "сделать активным"
                textSize = 10f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(MarsTheme.TEXT_MUTED)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = MarsTheme.pill(this@LocalProviderSettingsActivity, MarsTheme.SURFACE_SUBTLE, MarsTheme.BORDER)
                isClickable = true
                isFocusable = true
                contentDescription = "Сделать профиль ${profile.title} активным"
                setOnClickListener { setActiveProfile(profile) }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        }

        row.addView(
            MarsTheme.circleIconButton(
                this,
                R.drawable.ic_trash,
                "Удалить профиль ${profile.title}",
                bg = 0,
                iconTint = MarsTheme.TEXT_MUTED,
                sizeDp = 32,
                strokeColor = 0,
            ).apply { setOnClickListener { confirmDelete(profile) } },
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(2) },
        )
        return row
    }

    private fun setActiveProfile(profile: LocalProviderProfile) {
        runCatching { store.setActive(profile.id) }
            .onFailure { setStatus("Не переключилось: ${it.message ?: "ошибка"}", true) }
        renderProfiles()
        setStatus("Активный профиль: ${profile.title}", false)
    }

    private fun newProfile() {
        draftingNew = true
        editingId = null
        keySaved = false
        fillForm(null)
        renderProfiles()
        setStatus("Новый профиль: выберите модель, заполните адрес и ключ", false)
    }

    private fun fillForm(profile: LocalProviderProfile?) {
        editingId = profile?.id
        titleField.setText(profile?.title.orEmpty())
        // Пустой профиль (новая настройка) получает заводской адрес: пустые поля с
        // подсказкой выглядели «заполненными», а код читал пустоту.
        baseUrlField.setText(profile?.baseUrl?.takeIf { it.isNotBlank() } ?: LocalProviderDefaults.BASE_URL)
        keyField.setText("")
        keySaved = profile != null && !runCatching { store.apiKey(profile.id) }.getOrNull().isNullOrBlank()
        applyKeyHint()

        val model = profile?.model?.takeIf { it.isNotBlank() } ?: LocalProviderDefaults.MODEL
        selectedModel = model
        modelAtFill = model
        val known = catalogCache.firstOrNull { it.id == model }
        selectedContextWindow = known?.contextWindow ?: profile?.contextWindow
        selectedReasoningEffort = profile?.reasoningEffort
        // Без каталога поддержку reasoning не угадываем: единственный честный признак
        // здесь — уже сохранённый уровень (значит, провайдер его принимал).
        selectedSupportsReasoning = known?.supportsReasoningEffort ?: (profile?.reasoningEffort != null)
        temperatureField.setText(profile?.temperature?.let { formatTemperature(it) }.orEmpty())
        maxTokensField.setText(profile?.maxTokens?.toString().orEmpty())
        updateModelUi()
    }

    private fun applyKeyHint() {
        keyField.hint = if (keySaved) "Ключ сохранён ••••" else "Ключ API"
    }

    private fun confirmDelete(profile: LocalProviderProfile) {
        lifecycleScope.launch {
            val allowed = LocalChatConfirmDialog.ask(
                activity = this@LocalProviderSettingsActivity,
                title = "Удалить профиль?",
                body = "«${profile.title}» и его ключ будут удалены с телефона.",
                confirmLabel = "Удалить",
                denyLabel = "Отмена",
            )
            if (!allowed) return@launch
            runCatching { store.removeProfile(profile.id) }
                .onFailure { setStatus("Не удалилось: ${it.message ?: "ошибка"} ", true) }
            if (editingId == profile.id) {
                draftingNew = false
                editingId = null
                keySaved = false
                fillForm(null)
            }
            renderProfiles()
            setStatus("Профиль удалён", false)
        }
    }

    // --- выбор модели --------------------------------------------------------------------

    /** Блок «Модель»: кнопка выбора из каталога вместо текстового поля. */
    private fun modelBlock(): View {
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        block.addView(TextView(this).apply {
            text = "Модель"
            textSize = 11f
            setTextColor(MarsTheme.TEXT_MUTED)
            setPadding(0, dp(8), 0, dp(4))
        })
        modelButton = TextView(this).apply {
            textSize = 14f
            setTextColor(MarsTheme.TEXT)
            background = MarsTheme.button(
                this@LocalProviderSettingsActivity,
                MarsTheme.CODE_BG,
                radiusDp = 12,
                strokeColor = MarsTheme.BORDER_MUTED,
            )
            setPadding(dp(14), dp(12), dp(14), dp(12))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            isClickable = true
            isFocusable = true
            compoundDrawablePadding = dp(8)
            setOnClickListener { chooseModel() }
        }
        block.addView(modelButton, LinearLayout.LayoutParams(-1, -2))
        modelHint = TextView(this).apply {
            textSize = 11.5f
            setTextColor(MarsTheme.TEXT_MUTED)
            setPadding(dp(2), dp(4), dp(2), 0)
        }
        block.addView(modelHint, LinearLayout.LayoutParams(-1, -2))
        return block
    }

    /** Блок reasoning целиком скрыт, пока модель его не поддерживает. */
    private fun reasoningRow(): View {
        reasoningBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        reasoningBlock.addView(TextView(this).apply {
            text = "Reasoning"
            textSize = 11f
            setTextColor(MarsTheme.TEXT_MUTED)
            setPadding(0, dp(10), 0, dp(4))
        })
        reasoningButton = TextView(this).apply {
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(MarsTheme.ACCENT_TEXT)
            background = MarsTheme.button(
                this@LocalProviderSettingsActivity,
                MarsTheme.SURFACE_SUBTLE,
                radiusDp = 999,
                strokeColor = MarsTheme.ACCENT_BORDER,
            )
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { chooseReasoning() }
        }
        reasoningBlock.addView(reasoningButton, LinearLayout.LayoutParams(-2, -2))
        return reasoningBlock
    }

    private fun updateModelUi() {
        if (!::modelButton.isInitialized) return
        modelButton.text = selectedModel.ifBlank { "Выбрать модель" }
        modelButton.contentDescription = "Модель: ${selectedModel.ifBlank { "не выбрана" }}. " +
            "Нажмите, чтобы выбрать из каталога провайдера"
        val window = selectedContextWindow
        modelHint.text = when {
            window != null && selectedSupportsReasoning ->
                "Окно контекста: ${compactTokens(window.toLong())} токенов · reasoning поддерживается"
            window != null -> "Окно контекста: ${compactTokens(window.toLong())} токенов"
            selectedSupportsReasoning -> "Окно контекста неизвестно · reasoning поддерживается"
            else -> "Окно контекста неизвестно · «Проверить» уточнит по каталогу"
        }
        reasoningBlock.visibility = if (selectedSupportsReasoning) View.VISIBLE else View.GONE
        val effort = selectedReasoningEffort ?: REASONING_AUTO
        reasoningButton.text = "Reasoning: $effort"
        reasoningButton.contentDescription = "Уровень размышлений: $effort. Нажмите, чтобы изменить"
    }

    /** Загрузить каталог и показать выбор. Ключ уходит только в клиент, не в UI/лог. */
    private fun chooseModel() {
        val baseUrl = LocalProviderUrls.normalizeBaseUrl(baseUrlField.text.toString())
        if (baseUrl.isBlank()) {
            setStatus("Укажите адрес провайдера", true)
            return
        }
        val key = currentKey()
        if (key.isBlank()) {
            setStatus("Укажите ключ API: без него каталог моделей не отдаётся", true)
            return
        }
        val probe = probeProfile(baseUrl)
        setStatus("Загружаю каталог моделей…", false)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.catalog(probe, key) }.getOrElse { Result.failure(it) }
            }
            if (isFinishing || isDestroyed) return@launch
            val fetched = result.getOrNull().orEmpty()
            if (fetched.isNotEmpty()) {
                catalogCache = fetched
                catalogBaseUrl = baseUrl
            }
            val models = fetched.ifEmpty { cachedCatalog(baseUrl) }
            if (models.isEmpty()) {
                setStatus(
                    "Каталог недоступен: " + (result.exceptionOrNull()?.message ?: "провайдер не ответил"),
                    true,
                )
                return@launch
            }
            setStatus(
                if (fetched.isEmpty()) {
                    "Каталог из кэша · моделей ${models.size}"
                } else {
                    "Моделей ${models.size} · выберите нужную"
                },
                false,
            )
            showModelPicker(models)
        }
    }

    private fun cachedCatalog(baseUrl: String): List<LocalModelInfo> =
        if (catalogBaseUrl == baseUrl) catalogCache else emptyList()

    /**
     * Диалог выбора модели: поиск по имени, окно контекста и метка `reasoning` в каждой
     * строке. Список в собственном скролле фиксированной высоты — иначе 85 строк растянули
     * бы окно за пределы экрана.
     */
    private fun showModelPicker(models: List<LocalModelInfo>) {
        val ordered = models.sortedBy { it.id.lowercase(Locale.ROOT) }
        var dialog: android.app.Dialog? = null
        dialog = MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, "Модель", ordered.size.toString()))
            val search = pickerSearchField()
            root.addView(
                search,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
            )
            val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            root.addView(
                ScrollView(this).apply {
                    isFillViewport = false
                    isVerticalScrollBarEnabled = true
                    addView(listBox, ViewGroup.LayoutParams(-1, -2))
                },
                LinearLayout.LayoutParams(-1, dp(340)),
            )
            val footer = TextView(this).apply {
                textSize = 11f
                setTextColor(MarsTheme.TEXT_MUTED)
                visibility = View.GONE
                setPadding(0, dp(6), 0, 0)
            }
            root.addView(footer, LinearLayout.LayoutParams(-1, -2))

            fun render(raw: String) {
                listBox.removeAllViews()
                val needle = raw.trim().lowercase(Locale.ROOT)
                val visible = if (needle.isEmpty()) {
                    ordered
                } else {
                    ordered.filter { it.id.lowercase(Locale.ROOT).contains(needle) }
                }
                footer.visibility = View.GONE
                if (visible.isEmpty()) {
                    listBox.addView(
                        SectionStateViews.empty(this, "Ничего не найдено: уточните запрос")
                    )
                    return
                }
                visible.forEach { info ->
                    listBox.addView(
                        modelRow(info) {
                            applyModelInfo(info)
                            setStatus(
                                "Модель: ${info.id}" +
                                    (info.contextWindow?.let { " · окно " + compactTokens(it.toLong()) } ?: ""),
                                false,
                            )
                            dialog?.dismiss()
                        },
                        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) },
                    )
                }
            }

            render("")
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    render(s?.toString().orEmpty())
                }

                override fun afterTextChanged(s: Editable?) = Unit
            })

            root.addView(
                MarsDialog.quietButton(this, "Закрыть") { dialog?.dismiss() },
                LinearLayout.LayoutParams(-2, -2),
            )
            emptyList()
        }
    }

    private fun pickerSearchField(): EditText = EditText(this).apply {
        hint = "Поиск модели…"
        setHintTextColor(MarsTheme.TEXT_MUTED)
        setTextColor(MarsTheme.TEXT_STRONG)
        textSize = 14f
        background = MarsTheme.card(this@LocalProviderSettingsActivity, MarsTheme.SURFACE, radiusDp = 12)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
    }

    /** Строка каталога: имя, окно контекста, максимум ответа, метка reasoning. */
    private fun modelRow(info: LocalModelInfo, onPick: () -> Unit): View {
        val selected = info.id == selectedModel
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = MarsTheme.button(
                this@LocalProviderSettingsActivity,
                if (selected) MarsTheme.ACCENT_BG else MarsTheme.SURFACE_CARD,
                radiusDp = 12,
                strokeColor = if (selected) MarsTheme.ACCENT_BORDER else MarsTheme.BORDER,
            )
            isClickable = true
            isFocusable = true
            contentDescription = rowDescription(info, selected)
            setOnClickListener { onPick() }
        }
        row.addView(TextView(this).apply {
            text = info.id
            textSize = 13.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(MarsTheme.TEXT_STRONG)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })

        val badges = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val window = info.contextWindow
        badges.addView(
            badge(if (window != null) "окно " + compactTokens(window.toLong()) else "окно неизвестно"),
            LinearLayout.LayoutParams(-2, -2),
        )
        val maxOutput = info.maxOutputTokens
        if (maxOutput != null) {
            badges.addView(
                badge("ответ " + compactTokens(maxOutput.toLong())),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) },
            )
        }
        if (info.supportsReasoningEffort) {
            badges.addView(
                badge("reasoning", accent = true),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) },
            )
        }
        row.addView(badges, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        return row
    }

    private fun rowDescription(info: LocalModelInfo, selected: Boolean): String {
        val window = info.contextWindow
        val parts = ArrayList<String>(4)
        parts.add(info.id)
        parts.add(if (window != null) "окно " + compactTokens(window.toLong()) else "окно неизвестно")
        if (info.supportsReasoningEffort) parts.add("поддерживает reasoning")
        if (selected) parts.add("выбрана")
        return parts.joinToString(", ")
    }

    private fun badge(label: String, accent: Boolean = false): TextView = TextView(this).apply {
        text = label
        textSize = 10f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(if (accent) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_MUTED)
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = MarsTheme.pill(
            this@LocalProviderSettingsActivity,
            if (accent) MarsTheme.ACCENT_BG else MarsTheme.SURFACE_SUBTLE,
            if (accent) MarsTheme.ACCENT_BORDER else MarsTheme.BORDER,
        )
    }

    private fun applyModelInfo(info: LocalModelInfo) {
        selectedModel = info.id
        selectedContextWindow = info.contextWindow
        selectedSupportsReasoning = info.supportsReasoningEffort
        // Модель без reasoning не должна тащить в запрос чужой уровень.
        if (!info.supportsReasoningEffort) selectedReasoningEffort = null
        updateModelUi()
        persistPick()
    }

    /**
     * Сохранить выбор модели сразу, не дожидаясь «Сохранить».
     *
     * Иначе выбор в этом экране — только состояние формы: человек видит «Модель: X»
     * в статусной строке, уходит назад, и чат продолжает отвечать прежней моделью.
     * Пишутся ровно три поля выбора (`model`, окно, `reasoning_effort`); адрес, ключ,
     * temperature и max tokens остаются за кнопкой «Сохранить».
     *
     * Ничего не пишется, если профиль ещё не сохранён ([draftingNew] или пустой [editingId]):
     * тогда записывать некуда, и «Сохранить» создаст профиль целиком.
     */
    private fun persistPick() {
        if (draftingNew) return
        val id = editingId ?: return
        val stored = runCatching { store.state() }
            .getOrNull()?.profiles?.firstOrNull { it.id == id } ?: return
        val model = selectedModel.trim()
        if (model.isBlank()) return
        if (stored.model == model &&
            stored.contextWindow == selectedContextWindow &&
            stored.reasoningEffort == selectedReasoningEffort
        ) {
            return
        }
        runCatching {
            store.saveProfile(
                stored.copy(
                    model = model,
                    contextWindow = selectedContextWindow,
                    reasoningEffort = selectedReasoningEffort,
                )
            )
        }
        MarsModelLog.log("settings pick id=$id model=$model active=${store.state().active?.model}")
    }

    /**
     * Возврат на экран: модель могли сменить в чате, пока этот экран висел в фоне.
     *
     * Форма держит собственную копию [selectedModel], и «Сохранить» вернуло бы старую
     * модель поверх выбранной в чате — ровно та жалоба «выбрал модель, отвечает прежняя».
     * Подтягиваем сохранённую модель только если на этом экране её НЕ трогали
     * (`selectedModel` всё ещё равен значению, подставленному при заполнении формы):
     * несохранённый выбор человека не перетирается.
     */
    override fun onResume() {
        super.onResume()
        if (draftingNew) return
        val id = editingId ?: return
        if (selectedModel != modelAtFill) return
        val stored = runCatching { store.state() }
            .getOrNull()?.profiles?.firstOrNull { it.id == id } ?: return
        if (stored.model == selectedModel || stored.model.isBlank()) return
        selectedModel = stored.model
        modelAtFill = stored.model
        selectedContextWindow = stored.contextWindow
        selectedReasoningEffort = stored.reasoningEffort
        selectedSupportsReasoning = stored.reasoningEffort != null ||
            catalogCache.firstOrNull { it.id == stored.model }?.supportsReasoningEffort == true
        updateModelUi()
    }

    // --- reasoning -----------------------------------------------------------------------

    /** Пункты Авто/low/medium/high; вызывается только при поддержке моделью. */
    private fun chooseReasoning() {
        if (!selectedSupportsReasoning) return
        val options = ArrayList<Pair<String?, String>>(REASONING_LEVELS.size + 1)
        options.add(null to REASONING_AUTO)
        REASONING_LEVELS.forEach { level -> options.add(level to level) }
        var dialog: android.app.Dialog? = null
        dialog = MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, "Уровень размышлений", null))
            root.addView(
                MarsDialog.paragraph(
                    this,
                    "Уходит в запрос как reasoning_effort. «Авто» — поле не отправляется, " +
                        "уровень выбирает провайдер.",
                )
            )
            root.addView(
                MarsDialog.pillStack(
                    this,
                    options.map { (value, label) ->
                        val mark = if (value == selectedReasoningEffort) "$label · выбрано" else label
                        mark to {
                            selectedReasoningEffort = value
                            updateModelUi()
                            setStatus("Reasoning: $label", false)
                            dialog?.dismiss()
                        }
                    },
                )
            )
            emptyList()
        }
    }

    // --- проверка и сохранение -----------------------------------------------------------

    /** «Проверить»: каталог через [LocalChatClient.catalog] — адрес, ключ и окно модели. */
    private fun verify() {
        val baseUrl = LocalProviderUrls.normalizeBaseUrl(baseUrlField.text.toString())
        if (baseUrl.isBlank()) {
            setStatus("Укажите адрес провайдера", true)
            return
        }
        val key = currentKey()
        if (key.isBlank()) {
            setStatus("Укажите ключ API", true)
            return
        }
        val probe = probeProfile(baseUrl)
        setStatus("Проверяем адрес, ключ и каталог моделей…", false)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.catalog(probe, key) }.getOrElse { Result.failure(it) }
            }
            if (isFinishing || isDestroyed) return@launch
            val models = result.getOrNull()
            if (models == null) {
                setStatus("Не прошло: " + (result.exceptionOrNull()?.message ?: "без текста"), true)
                return@launch
            }
            if (models.isEmpty()) {
                setStatus("Сервер ответил, но каталог моделей пуст", true)
                return@launch
            }
            catalogCache = models
            catalogBaseUrl = baseUrl
            val info = models.firstOrNull { it.id == selectedModel }
            // Каталог — единственный честный источник окна и поддержки reasoning:
            // после проверки экран показывает то же, что увидит чат.
            if (info != null) applyModelInfo(info)
            setStatus(verifySummary(models, info), false)
        }
    }

    private fun verifySummary(models: List<LocalModelInfo>, info: LocalModelInfo?): String {
        val parts = ArrayList<String>(4)
        parts.add("OK · моделей ${models.size}")
        if (info == null) {
            parts.add("выбранной модели нет в каталоге")
        } else {
            val window = info.contextWindow
            parts.add(if (window != null) "окно " + compactTokens(window.toLong()) else "окно неизвестно")
            val maxOutput = info.maxOutputTokens
            if (maxOutput != null) parts.add("макс. ответ " + compactTokens(maxOutput.toLong()))
            if (info.supportsReasoningEffort) parts.add("reasoning")
        }
        return parts.joinToString(" · ")
    }

    private fun save() {
        val baseUrl = LocalProviderUrls.normalizeBaseUrl(baseUrlField.text.toString())
        if (baseUrl.isBlank()) {
            setStatus("Укажите адрес провайдера", true)
            return
        }
        val model = selectedModel.trim()
        if (model.isBlank()) {
            setStatus("Выберите модель: кнопка «Модель» выше", true)
            return
        }
        var temperature: Double?
        var maxTokens: Int?
        try {
            temperature = parseTemperature()
            maxTokens = parseMaxTokens()
        } catch (invalid: IllegalArgumentException) {
            setStatus(invalid.message ?: "Проверьте temperature и max tokens", true)
            return
        }

        val id = editingId ?: UUID.randomUUID().toString()
        val profile = LocalProviderProfile(
            id = id,
            title = titleField.text.toString().trim().ifBlank { model },
            baseUrl = baseUrl,
            model = model,
            temperature = temperature,
            maxTokens = maxTokens,
            reasoningEffort = if (selectedSupportsReasoning) selectedReasoningEffort else null,
            contextWindow = selectedContextWindow,
        )

        runCatching { store.saveProfile(profile) }.onFailure { error ->
            setStatus("Не сохранилось: ${error.message ?: "ошибка"}", true)
            return
        }

        val key = keyField.text.toString().trim()
        if (key.isNotEmpty()) {
            runCatching { store.saveApiKey(id, key) }.onFailure { error ->
                setStatus("Ключ не сохранился: ${error.message ?: "ошибка"}", true)
                return
            }
            // Поле очищается: секрет не должен оставаться ни в UI, ни в состоянии View.
            keyField.setText("")
        }
        runCatching { store.setActive(id) }

        editingId = id
        draftingNew = false
        keySaved = !runCatching { store.apiKey(id) }.getOrNull().isNullOrBlank()
        applyKeyHint()
        baseUrlField.setText(profile.baseUrl)
        renderProfiles()
        setStatus(saveSummary(profile), false)
    }

    private fun saveSummary(profile: LocalProviderProfile): String {
        val window = profile.contextWindow
        val tail = buildString {
            if (window != null) append(" · окно ").append(compactTokens(window.toLong()))
            profile.reasoningEffort?.let { append(" · reasoning ").append(it) }
            profile.temperature?.let { append(" · t=").append(formatTemperature(it)) }
            profile.maxTokens?.let { append(" · max ").append(it) }
        }
        return "Сохранено · ${profile.title}$tail"
    }

    private fun currentKey(): String {
        val typed = keyField.text.toString().trim()
        if (typed.isNotEmpty()) return typed
        return editingId?.let { id -> runCatching { store.apiKey(id) }.getOrNull() }.orEmpty()
    }

    private fun probeProfile(baseUrl: String): LocalProviderProfile = LocalProviderProfile(
        id = editingId ?: "probe",
        title = titleField.text.toString().trim().ifBlank {
            selectedModel.ifBlank { LocalProviderDefaults.TITLE }
        },
        baseUrl = baseUrl,
        model = selectedModel.trim(),
    )

    // --- разбор чисел --------------------------------------------------------------------

    /** `null` — поле пустое (необязательный параметр), исключение — мусор/выход из диапазона. */
    private fun parseTemperature(): Double? {
        val raw = temperatureField.text.toString().trim().replace(',', '.')
        if (raw.isEmpty()) return null
        val value = raw.toDoubleOrNull()
            ?: throw IllegalArgumentException("Temperature: нужно число, например 0.7")
        if (value < MIN_TEMPERATURE || value > MAX_TEMPERATURE) {
            throw IllegalArgumentException("Temperature: допустимо от 0 до 2")
        }
        return value
    }

    private fun parseMaxTokens(): Int? {
        val raw = maxTokensField.text.toString().trim().replace(" ", "").replace("\u00A0", "")
        if (raw.isEmpty()) return null
        val value = raw.toIntOrNull()
            ?: throw IllegalArgumentException("Max tokens: нужно целое число")
        if (value < MIN_MAX_TOKENS || value > MAX_MAX_TOKENS) {
            throw IllegalArgumentException("Max tokens: допустимо от 1 до $MAX_MAX_TOKENS")
        }
        return value
    }

    private fun formatTemperature(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    /** 200 000 → «200.0k»; неизвестное окно не подменяется нулём. */
    private fun compactTokens(value: Long): String = when {
        value >= 1_000_000L -> String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0)
        value >= 1_000L -> String.format(Locale.ROOT, "%.1fk", value / 1_000.0)
        else -> value.toString()
    }

    // --- мелкая разметка -----------------------------------------------------------------

    private fun sectionHeader(title: String, action: (() -> Unit)?): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(6))
        }
        row.addView(
            TextView(this).apply {
                text = title.uppercase()
                textSize = 10f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(MarsTheme.TEXT_MUTED)
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        if (action != null) {
            row.addView(
                MarsTheme.circleIconButton(
                    this,
                    R.drawable.ic_add,
                    "Новый профиль",
                    bg = MarsTheme.SURFACE,
                    iconTint = MarsTheme.TEXT,
                    sizeDp = 32,
                    strokeColor = MarsTheme.BORDER,
                ).apply { setOnClickListener { action() } },
                LinearLayout.LayoutParams(dp(40), dp(40)),
            )
        }
        return row
    }

    private fun fieldBlock(label: String, field: View): View {
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        block.addView(TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(MarsTheme.TEXT_MUTED)
            setPadding(0, dp(8), 0, dp(4))
        })
        block.addView(field, LinearLayout.LayoutParams(-1, -2))
        return block
    }

    private fun field(hint: String, variation: Int = InputType.TYPE_TEXT_VARIATION_NORMAL): EditText =
        EditText(this).apply {
            this.hint = hint
            setHintTextColor(MarsTheme.TEXT_MUTED)
            setTextColor(MarsTheme.TEXT)
            textSize = 14f
            background = MarsTheme.card(this@LocalProviderSettingsActivity, MarsTheme.CODE_BG, 12, MarsTheme.BORDER_MUTED)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or variation
        }

    /** Числовое поле: десятичная точка разрешена только для temperature. */
    private fun numberField(hint: String, decimal: Boolean): EditText =
        EditText(this).apply {
            this.hint = hint
            setHintTextColor(MarsTheme.TEXT_MUTED)
            setTextColor(MarsTheme.TEXT)
            textSize = 14f
            background = MarsTheme.card(this@LocalProviderSettingsActivity, MarsTheme.CODE_BG, 12, MarsTheme.BORDER_MUTED)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            maxLines = 1
            inputType = InputType.TYPE_CLASS_NUMBER or
                (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
        }

    /** Компактная кнопка-пилюля: без крупных CTA, тап-цель не меньше 48dp по высоте. */
    private fun actionPill(label: String, accent: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (accent) MarsTheme.TEXT_STRONG else MarsTheme.ACCENT_TEXT)
            background = MarsTheme.button(
                this@LocalProviderSettingsActivity,
                if (accent) MarsTheme.ACCENT else MarsTheme.SURFACE_SUBTLE,
                radiusDp = 999,
                strokeColor = if (accent) 0 else MarsTheme.ACCENT_BORDER,
            )
            val padH = dp(18)
            setPadding(padH, dp(12), padH, dp(12))
            minHeight = dp(48)
            minWidth = dp(48)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun setStatus(text: String, error: Boolean) {
        status.text = text
        status.setTextColor(if (error) MarsTheme.STATUS_WARNING else MarsTheme.TEXT_MUTED)
        status.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun dp(value: Int): Int = MarsTheme.dp(this, value)
}
