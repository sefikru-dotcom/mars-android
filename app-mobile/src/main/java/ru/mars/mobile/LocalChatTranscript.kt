package ru.mars.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ru.mars.network.local.LocalRole

/**
 * Лента локального диалога: пузыри, markdown-ответы, карточки инструментов, автоскролл.
 *
 * Рендер инкрементальный: пока набор строк тот же (меняются только текст и статусы),
 * вьюхи не пересобираются — стрим обновляет уже существующую карточку. Полная
 * пересборка только при изменении состава строк (новое сообщение, карточка).
 *
 * Ответы ассистента рисует **существующий** [ChatMarkdownRenderer] (тот же, что в
 * серверном чате): код, списки, таблицы, ссылки и цитаты выглядят одинаково в обоих
 * чатах, и второго markdown-парсера в проекте не появляется. Поэтому тело ответа —
 * не один `TextView`, а контейнер markdown-единиц.
 *
 * Долгое нажатие на сообщение отдаётся наружу ([onMessageLongPress]): лист действий —
 * это диалог, а лента диалогов не показывает.
 *
 * Палитра — только [MarsTheme]: своих цветов у экрана нет, как и у остальных разделов.
 */
internal class LocalChatTranscript(
    private val context: Context,
    /** Долгое нажатие на сообщение; второй аргумент — «это последний ответ ассистента». */
    private val onMessageLongPress: (LocalChatItem, Boolean) -> Unit,
    /** Копирование из блока кода (кнопка «копировать» внутри markdown). */
    private val onCopyText: (String) -> Unit,
    /** Открытие ссылки/картинки из markdown. */
    private val onOpenUrl: (String) -> Unit,
    /** «Сохранить в галерею» на сгенерированной картинке; вторая картинка не нужна. */
    private val onSaveImage: (java.io.File) -> Unit = {},
    /**
     * Копия картинки из истории по id TOOL-сообщения: байты, развёрнутые из текста
     * сообщения. Нужна, когда файл в cacheDir уже вычищен системой, а диалог открыт снова.
     */
    private val onStoredImage: (String) -> ByteArray? = { null },
) {

    private val column: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(2), 0, dp(6))
    }

    /** Скролл наружу: экран вешает его в контент-слот [SectionScaffold]. */
    val view: ScrollView = ScrollView(context).apply {
        isFillViewport = true
        // Горизонтальный скролл запрещён: длинные строки переносятся внутри пузыря,
        // а длинный код скроллится своим HorizontalScrollView (изолированный скролл),
        // а не растягивает экран (POCO F5 — узкий портрет).
        isHorizontalScrollBarEnabled = false
        addView(column, ViewGroup.LayoutParams(-1, -2))
    }

    private var renderedIds: List<String> = emptyList()
    private val userBubbles = HashMap<String, TextView>()
    private val assistantCards = HashMap<String, AssistantCard>()
    private val toolRows = HashMap<String, ToolRow>()

    /** id последнего ответа ассистента: «Повторить» существует только для него. */
    private var lastAssistantId: String? = null

    /** Раскрытые блоки кода: ключ — (индекс сообщения, индекс блока). */
    private val expandedCode = HashSet<Long>()

    /**
     * Показать состояние. Скролл — только если человек уже был внизу: иначе чтение
     * середины диалога обрывалось бы на каждой дельте стрима.
     */
    fun render(state: LocalChatUiState) {
        val atBottom = isAtBottom()
        lastAssistantId = state.items
            .lastOrNull { it.role == LocalRole.ASSISTANT && it.tool == null }
            ?.id
        val ids = state.items.map { it.id }
        if (ids != renderedIds) rebuild(state) else update(state)
        if (atBottom) scrollToBottom()
    }

    /** Принудительно вниз — после отправки нового сообщения. */
    fun scrollToBottom() {
        view.post { runCatching { view.fullScroll(View.FOCUS_DOWN) } }
    }

    private fun rebuild(state: LocalChatUiState) {
        column.removeAllViews()
        userBubbles.clear()
        assistantCards.clear()
        toolRows.clear()
        renderedIds = state.items.map { it.id }

        if (state.items.isEmpty()) {
            column.addView(emptyHint(), LinearLayout.LayoutParams(-1, -2))
            return
        }

        state.items.forEachIndexed { index, item ->
            val tool = item.tool
            if (tool != null) {
                val row = ToolRow(context)
                row.bind(tool)
                toolRows[item.id] = row
                column.addView(
                    row.root,
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) },
                )
                return@forEachIndexed
            }
            if (item.role == LocalRole.USER) {
                column.addView(
                    userBubble(item),
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
                )
            } else {
                val card = AssistantCard(item)
                assistantCards[item.id] = card
                card.bind(item, index)
                column.addView(
                    card.root,
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
                )
            }
        }
    }

    private fun update(state: LocalChatUiState) {
        state.items.forEachIndexed { index, item ->
            val tool = item.tool
            if (tool != null) {
                toolRows[item.id]?.bind(tool)
                return@forEachIndexed
            }
            if (item.role == LocalRole.USER) {
                userBubbles[item.id]?.text = item.text
                return@forEachIndexed
            }
            assistantCards[item.id]?.bind(item, index)
        }
    }

    /** Пузырь пользователя: справа, сапфировый градиент, ограничение по ширине. */
    private fun userBubble(item: LocalChatItem): View {
        val text = TextView(context).apply {
            this.text = item.text
            textSize = 14f
            setTextColor(MarsTheme.TEXT_STRONG)
            setLineSpacing(0f, 1.15f)
            background = MarsTheme.userBubble(context)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            // Длинное сообщение не должно упираться в край: пузырь занимает не больше
            // 84% ширины, остальное — воздух (как в WebUI).
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.84f).toInt()
            // Выделение текста живёт в листе действий («Выбрать текст»): textIsSelectable
            // перехватывает долгое нажатие, и лист тогда не открывается вовсе.
        }
        userBubbles[item.id] = text
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(text, LinearLayout.LayoutParams(-2, -2))
        }
        attachLongPress(row, item)
        attachLongPress(text, item)
        return row
    }

    private fun emptyHint(): View = TextView(context).apply {
        text = "Пока пусто. Сообщение уйдёт провайдеру напрямую — сервер Марса для этого " +
            "чата не нужен."
        textSize = 13f
        setTextColor(MarsTheme.TEXT_MUTED)
        setLineSpacing(0f, 1.2f)
        setPadding(dp(4), dp(12), dp(4), dp(12))
    }

    /** Долгое нажатие на вьюху и на всех её потомках: markdown-абзацы перехватывают тач. */
    private fun attachLongPress(view: View, item: LocalChatItem) {
        view.setOnLongClickListener {
            onMessageLongPress(item, item.id == lastAssistantId)
            true
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                attachLongPress(view.getChildAt(index), item)
            }
        }
    }

    private fun isAtBottom(): Boolean {
        val child = view.getChildAt(0) ?: return true
        if (child.height == 0) return true
        return child.height - view.height - view.scrollY <= dp(24)
    }

    private fun dp(value: Int): Int = MarsTheme.dp(context, value)

    private fun codeKey(messageIndex: Int, blockIndex: Int): Long =
        (messageIndex.toLong() shl 32) or (blockIndex.toLong() and 0xffffffffL)

    /**
     * Карточка ответа ассистента: подпись «Марс» и тело, нарисованное
     * [ChatMarkdownRenderer]. Пока ответ пуст и идёт стрим — курсивная заглушка.
     */
    private inner class AssistantCard(item: LocalChatItem) {

        val root: LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = MarsTheme.botMessageCard(context)
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }

        private val placeholder: TextView = TextView(context).apply {
            text = STREAMING_PLACEHOLDER
            textSize = 14f
            setTextColor(MarsTheme.TEXT_MUTED)
            typeface = Typeface.create("sans-serif", Typeface.ITALIC)
            visibility = View.GONE
        }

        private val body: LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        private val markdownState = ChatMarkdownRenderer.MarkdownRenderState()

        private var current: LocalChatItem = item
        private var index: Int = 0

        init {
            root.addView(TextView(context).apply {
                text = "Марс"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(MarsTheme.TEXT_MUTED)
            })
            root.addView(placeholder, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            root.addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            attachLongPress(root, item)
        }

        fun bind(item: LocalChatItem, index: Int) {
            current = item
            this.index = index
            attachLongPress(root, item)
            if (item.streaming && item.text.isBlank()) {
                placeholder.visibility = View.VISIBLE
                body.visibility = View.GONE
                return
            }
            placeholder.visibility = View.GONE
            body.visibility = View.VISIBLE
            drawMarkdown()
        }

        /**
         * Отрисовка markdown. [ChatMarkdownRenderer.render] сам решает, какой префикс
         * переиспользовать: на дельтах стрима перерисовывается только хвост, поэтому
         * лента не мигает и не теряет позицию скролла.
         */
        private fun drawMarkdown() {
            ChatMarkdownRenderer.render(
                context = context,
                blocks = MarkdownFormatter.parseBlocks(current.text),
                parent = body,
                user = false,
                messageIndex = index,
                state = markdownState,
                isCodeBlockExpanded = { message, block ->
                    expandedCode.contains(codeKey(message, block))
                },
                toggleCodeBlock = { message, block -> toggleCode(message, block) },
                copyMessage = onCopyText,
                // Своего лайтбокса у локального чата нет: картинка открывается как ссылка.
                openImage = onOpenUrl,
                openUrl = onOpenUrl,
            )
            attachLongPress(body, current)
        }

        /**
         * Раскрытие/сворачивание длинного блока кода. Набор markdown-единиц при этом не
         * меняется, поэтому инкрементальный рендер изменения не заметит — тело
         * перерисовывается с нуля (блоков кода в ответе единицы, это дешево).
         */
        private fun toggleCode(messageIndex: Int, blockIndex: Int) {
            val key = codeKey(messageIndex, blockIndex)
            if (!expandedCode.add(key)) expandedCode.remove(key)
            body.removeAllViews()
            markdownState.units = emptyList()
            drawMarkdown()
        }
    }

    /**
     * Карточка инструмента: свёрнута в одну строку (имя + статус + превью), по тапу
     * раскрывает аргументы. Отдельная кнопка не нужна — вся шапка кликабельна.
     *
     * У `generate_image` внутри карточки ещё и сама картинка: инструмент положил JPEG в
     * `cacheDir`, и человек ждёт её здесь, а не в настройках. Файл — первичный источник;
     * если систему вычистила кэш, берётся копия байтов из истории сообщения
     * (`GeneratedImageStorage`), и только если нет и её — карточка остаётся без картинки.
     */
    private inner class ToolRow(private val context: Context) {

        val root: LinearLayout
        private val name: TextView
        private val badge: TextView
        private val preview: TextView
        private val arguments: TextView
        private var expanded = false

        /** Блок сгенерированной картинки: виден только у `generate_image` с файлом. */
        private val imageBlock: LinearLayout

        private val imageView: android.widget.ImageView

        /** Кнопка «Сохранить в галерею»: есть только у картинки, которой это уместно. */
        private val saveButton: TextView

        /** Что сейчас нарисовано: путь + размер файла — чтобы не перечитывать диск зря. */
        private var drawnImage: String? = null

        init {
            root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = MarsTheme.toolCallCard(context)
                setPadding(dp(10), dp(6), dp(8), dp(6))
            }

            val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }

            val icon = android.widget.ImageView(context).apply {
                setImageDrawable(androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_chip))
                imageTintList = android.content.res.ColorStateList.valueOf(MarsTheme.ACCENT_TEXT)
            }
            header.addView(icon, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) })

            name = TextView(context).apply {
                textSize = 11.5f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(MarsTheme.TEXT_EM)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                minimumWidth = 0
            }
            header.addView(name, LinearLayout.LayoutParams(0, -2, 1f))

            badge = TextView(context).apply { textSize = 9.5f }
            header.addView(
                badge,
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) },
            )

            root.addView(header)
            header.setOnClickListener { toggle() }

            preview = TextView(context).apply {
                textSize = 11.5f
                setTextColor(MarsTheme.TEXT_MUTED)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                visibility = View.GONE
            }
            root.addView(preview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })

            arguments = TextView(context).apply {
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(MarsTheme.TEXT_MUTED)
                setTextIsSelectable(true)
                visibility = View.GONE
            }
            root.addView(arguments, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

            imageView = android.widget.ImageView(context).apply {
                adjustViewBounds = true
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                // Скругление — как у карточек темы, иначе JPEG с белым фоном «прилипает»
                // прямоугольником к тёмному пузырю.
                clipToOutline = true
                background = MarsTheme.card(context, MarsTheme.CODE_BG, radiusDp = 12)
                contentDescription = "Сгенерированная картинка"
                visibility = View.GONE
            }
            imageBlock = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            imageBlock.addView(imageView, LinearLayout.LayoutParams(-1, -2))

            saveButton = TextView(context).apply {
                text = "Сохранить в галерею"
                textSize = 12f
                setTypeface(Typeface.DEFAULT_BOLD)
                gravity = Gravity.CENTER
                setTextColor(MarsTheme.ACCENT_TEXT)
                background = MarsTheme.pill(context, MarsTheme.ACCENT_BG, MarsTheme.ACCENT_BORDER)
                setPadding(dp(14), dp(9), dp(14), dp(9))
                minHeight = dp(44)
                isClickable = true
                isFocusable = true
                visibility = View.GONE
            }
            imageBlock.addView(
                saveButton,
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) },
            )
            imageBlock.visibility = View.GONE
            root.addView(imageBlock, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }

        fun bind(card: LocalToolCard) {
            name.text = card.name
            MarsTheme.applyStatusBadge(badge, statusOf(card.phase))
            val result = card.preview?.trim().orEmpty()
            preview.text = result
            preview.visibility = if (result.isEmpty()) View.GONE else View.VISIBLE
            arguments.text = card.argumentsJson.ifBlank { "{}" }
            bindImage(card.imagePath)
            root.contentDescription = buildString {
                append(card.name)
                append(", ")
                append(statusLabel(card.phase))
                if (result.isNotEmpty()) {
                    append(", ")
                    append(result)
                }
            }
        }

        /**
         * Показать картинку инструмента.
         *
         * Перерисовка только при смене файла: `bind` зовётся на каждом кадре стрима и на
         * каждом обновлении статуса, а декодирование 1024×1024 не бесплатное.
         *
         * Ни файла, ни копии — пустого места без объяснения нет: в подпись карточки
         * добавляется «картинка удалена» (кэш чистится системой).
         */
        private fun bindImage(path: String?) {
            // Файл из cacheDir, а если его уже вычистили — копия байтов в истории.
            // Путь копии — не путь к файлу: id сообщения не содержит ни «/», ни «\\».
            val file = path?.takeIf { it.isNotBlank() }?.let(::imageFile)
            if (file == null) {
                if (drawnImage != null) {
                    drawnImage = null
                    imageView.setImageDrawable(null)
                    imageView.visibility = View.GONE
                    saveButton.visibility = View.GONE
                    imageBlock.visibility = View.GONE
                }
                return
            }
            val stamp = "${file.absolutePath}:${file.length()}"
            if (stamp == drawnImage) return
            drawnImage = stamp
            val bitmap = decodeThumbnail(file)
            if (bitmap == null) {
                // Файл вычищен системой (cacheDir) — картинку не нарисовать.
                imageView.setImageDrawable(null)
                imageView.visibility = View.GONE
                saveButton.visibility = View.GONE
                imageBlock.visibility = View.GONE
                root.contentDescription = root.contentDescription?.toString()
                    ?.let { "$it, картинка удалена" }
                return
            }
            imageView.setImageBitmap(bitmap)
            imageView.visibility = View.VISIBLE
            imageBlock.visibility = View.VISIBLE
            // Только API 29+ умеет писать в общий альбом без разрешений; на 28 кнопка
            // вела бы в тупик, поэтому её нет (см. GallerySaver.canSave).
            saveButton.visibility = if (GallerySaver.canSave()) View.VISIBLE else View.GONE
            saveButton.contentDescription = "Сохранить картинку в галерею"
            saveButton.setOnClickListener { onSaveImage(file) }
            imageView.setOnClickListener {
                // Тап по картинке — тоже сохранение: это единственное действие, которое
                // с ней имеет смысл, а системного просмотрщика для cache-файла нет.
                if (GallerySaver.canSave()) onSaveImage(file)
            }
        }

        /**
         * Файл картинки для показа: сначала cacheDir (полная копия уже скачана), затем
         * копия из истории, развёрнутая в приватный кэш.
         *
         * null — ни файла, ни копии.
         */
        private fun imageFile(handle: String): java.io.File? {
            val direct = java.io.File(handle)
            if (direct.isFile && direct.length() > 0L) return direct
            val bytes = onStoredImage(handle) ?: return null
            return runCatching {
                val dir = java.io.File(context.cacheDir, STORED_DIR).apply { mkdirs() }
                val target = java.io.File(dir, handle.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".jpeg")
                if (!target.isFile || target.length() != bytes.size.toLong()) {
                    target.writeBytes(bytes)
                }
                target
            }.getOrNull()
        }

        /** Декод с уменьшением: полный 4096×4096 битмап в списке съел бы память. */
        private fun decodeThumbnail(file: java.io.File): Bitmap? = runCatching {
            require(file.isFile && file.length() > 0L) { "файла нет" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val target = dp(900)
            var sample = 1
            while (bounds.outWidth / sample > target * 2) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, options)
        }.getOrNull()

        private fun toggle() {
            expanded = !expanded
            arguments.visibility = if (expanded) View.VISIBLE else View.GONE
            // Свёрнутое превью при раскрытии не нужно: аргументы важнее.
            preview.visibility = if (!expanded && preview.text.isNotEmpty()) View.VISIBLE else View.GONE
        }

        private fun statusOf(phase: LocalToolPhase): MarsGradients.Status = when (phase) {
            LocalToolPhase.PENDING, LocalToolPhase.RUNNING -> MarsGradients.Status.RUNNING
            LocalToolPhase.DONE -> MarsGradients.Status.DONE
            LocalToolPhase.FAILED -> MarsGradients.Status.ERROR
        }

        private fun statusLabel(phase: LocalToolPhase): String = when (phase) {
            LocalToolPhase.PENDING, LocalToolPhase.RUNNING -> "выполняется"
            LocalToolPhase.DONE -> "готово"
            LocalToolPhase.FAILED -> "ошибка"
        }

        private fun dp(value: Int): Int = MarsTheme.dp(context, value)
    }

    private companion object {
        const val STREAMING_PLACEHOLDER = "Печатает…"

        /** Каталог развёрнутых копий картинок из истории (внутри cacheDir). */
        const val STORED_DIR = "generated-history"
    }
}
