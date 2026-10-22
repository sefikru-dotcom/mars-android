package ru.mars.mobile

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.CronDeliveryOption
import ru.mars.network.model.CronJob
import ru.mars.network.model.CronRunSummary
import ru.mars.network.repository.CronJobsResult
import ru.mars.network.repository.CronMutationResult
import ru.mars.network.repository.CronRepository

/**
 * Планировщик: список задач + полный CRUD и история прогонов по серверным
 * контрактам /api/crons (create/update/delete/run/pause/resume/history/
 * run-detail/delivery-options). Вся валидация и форматирование — в чистой JVM
 * [CronCrudPolicy]; сеть — в [CronRepository]; диалоги — MarsTheme-примитивы
 * [MarsDialog]. Необратимое удаление — только через подтверждение.
 */
class CronActivity : ComponentActivity() {

    private val store by lazy { AndroidKeyStoreCredentialStore(this) }
    private val cronRepo = CronRepository()

    private lateinit var jobList: LinearLayout
    private lateinit var scaffold: SectionScaffold.Parts
    private lateinit var stateContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())
        loadJobs()
    }

    private fun buildView(): View {
        scaffold = SectionScaffold.create(this, "Планировщик", ::loadJobs)
        val content = scaffold.content

        // Компактная тихая кнопка создания — без визуально крупных элементов.
        val createRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        createRow.addView(
            MarsDialog.quietButton(this, "Новая задача", iconRes = R.drawable.ic_add) { showJobForm(null) },
        )
        content.addView(createRow, LinearLayout.LayoutParams(-1, -2))

        stateContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(stateContainer, LinearLayout.LayoutParams(-1, -2))

        jobList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(jobList, LinearLayout.LayoutParams(-1, -2))
            },
            LinearLayout.LayoutParams(-1, 0, 1f),
        )
        return scaffold.root
    }

    private fun loadJobs() {
        val cred = store.read()
        if (cred == null) {
            showState(SectionScreenState.Offline("Нужен вход: откройте приложение и пройдите пару"))
            return
        }
        jobList.removeAllViews()
        stateContainer.removeAllViews()
        stateContainer.addView(SectionStateViews.loading(this, "Загрузка задач…"))
        scaffold.progress.visibility = View.VISIBLE
        scaffold.statusLine.visibility = View.VISIBLE
        scaffold.statusLine.text = "Загрузка задач…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                cronRepo.loadJobs(ChatActivity.BASE_URL, cred)
            }
            when (result) {
                is CronJobsResult.Loaded -> renderJobs(result.response.jobs)
                is CronJobsResult.Failed -> showState(
                    if (result.offline) SectionScreenState.Offline("Нет связи с сервером")
                    else SectionScreenState.Error("Не удалось загрузить задачи"),
                )
            }
        }
    }

    private fun showState(state: SectionScreenState) {
        scaffold.progress.visibility = View.GONE
        scaffold.statusLine.visibility = View.GONE
        stateContainer.removeAllViews()
        val view = when (state) {
            SectionScreenState.Loading -> SectionStateViews.loading(this, "Загрузка задач…")
            is SectionScreenState.Empty -> SectionStateViews.empty(this, state.message)
            is SectionScreenState.Error -> SectionStateViews.error(this, state.message, ::loadJobs)
            is SectionScreenState.Offline -> SectionStateViews.offline(this, state.message)
        }
        stateContainer.addView(view)
    }

    private fun renderJobs(jobs: List<CronJob>) {
        scaffold.progress.visibility = View.GONE
        scaffold.statusLine.visibility = View.GONE
        jobList.removeAllViews()
        if (jobs.isEmpty()) {
            showState(SectionScreenState.Empty("Задачи планировщика отсутствуют"))
            return
        }
        stateContainer.removeAllViews()

        jobs.forEach { job ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = MarsTheme.card(this@CronActivity, MarsTheme.SURFACE, radiusDp = 12, strokeColor = MarsTheme.BORDER)
                setPadding(dp(14), dp(12), dp(14), dp(8))
            }

            // Row 1: Name and status badge
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val dot = MarsTheme.glowingStatusDot(
                this,
                if (job.enabled) MarsTheme.STATUS_ONLINE else MarsTheme.TEXT_MUTED
            )
            topRow.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })

            val nameView = TextView(this).apply {
                text = job.name
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(MarsTheme.TEXT_STRONG)
            }
            topRow.addView(nameView, LinearLayout.LayoutParams(0, -2, 1f))

            val statusBadge = TextView(this).apply {
                text = if (job.enabled) "Активна" else "На паузе"
                textSize = 11f
                setTextColor(if (job.enabled) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_MUTED)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = MarsTheme.pill(this@CronActivity, MarsTheme.SURFACE_0, strokeColor = MarsTheme.BORDER_SUBTLE)
            }
            topRow.addView(statusBadge)
            card.addView(topRow)

            // Row 2: Schedule & Model
            val scheduleText = job.scheduleDisplay ?: "По расписанию"
            val modelText = job.model?.let { " · $it" } ?: ""
            val subView = TextView(this).apply {
                text = "Расписание: $scheduleText$modelText"
                textSize = 12.5f
                setTextColor(MarsTheme.TEXT_MUTED)
                setPadding(0, dp(4), 0, 0)
            }
            card.addView(subView)

            // Row 3: Timings (next / last run)
            val nextRun = job.nextRunAt?.let { "След.: $it" } ?: ""
            val lastRun = job.lastRunAt?.let { "Посл.: $it (${job.lastStatus ?: "ok"})" } ?: ""
            val timingText = listOf(nextRun, lastRun).filter { it.isNotBlank() }.joinToString(" · ")
            if (timingText.isNotBlank()) {
                val timingView = TextView(this).apply {
                    text = timingText
                    textSize = 11.5f
                    setTextColor(MarsTheme.TEXT_MUTED)
                    setPadding(0, dp(2), 0, 0)
                }
                card.addView(timingView)
            }

            // Row 4: компактные тихие действия (run/pause/history/edit/delete)
            card.addView(buildActionRow(job))

            jobList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    /** Горизонтальная лента компактных текстовых действий задачи. */
    private fun buildActionRow(job: CronJob): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(4), 0, 0)
        }
        row.addView(cardAction("Запустить") { runJobNow(job) })
        row.addView(
            cardAction(if (job.enabled) "Пауза" else "Возобновить") { togglePauseNow(job) }
        )
        row.addView(cardAction("История") { showHistory(job) })
        row.addView(cardAction("Правка") { showJobForm(job) })
        val delete = cardAction("Удалить") { confirmDelete(job) }
        delete.setTextColor(MarsTheme.ACCENT_TEXT)
        row.addView(delete)
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun cardAction(label: String, onClick: () -> Unit): Button =
        MarsDialog.quietButton(this, label, onClick = onClick).apply {
            textSize = 12f
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }

    // ── Запуск / пауза ─────────────────────────────────────────────────────────

    private fun runJobNow(job: CronJob) {
        val cred = store.read() ?: return
        Toast.makeText(this, "Запуск задачи: ${job.name}…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { cronRepo.runJob(ChatActivity.BASE_URL, cred, job.id) }
            if (ok) {
                Toast.makeText(this@CronActivity, "Задача ${job.name} запущена", Toast.LENGTH_SHORT).show()
                loadJobs()
            } else {
                Toast.makeText(this@CronActivity, "Не удалось запустить задачу", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun togglePauseNow(job: CronJob) {
        val cred = store.read() ?: return
        val pause = job.enabled
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                cronRepo.togglePause(ChatActivity.BASE_URL, cred, job.id, pause)
            }
            Toast.makeText(
                this@CronActivity,
                if (ok) (if (pause) "Задача на паузе" else "Задача возобновлена") else "Сервер отклонил запрос",
                Toast.LENGTH_SHORT,
            ).show()
            if (ok) loadJobs()
        }
    }

    // ── Создание / правка задачи ───────────────────────────────────────────────

    /**
     * Форма задачи: имя (опц.), расписание, промпт, доставка (опции сервера).
     * [existing] == null — создание (POST /api/crons/create), иначе правка
     * (POST /api/crons/update). Валидация — [CronCrudPolicy.validateForm];
     * серверные ошибки показываются в error-strip без закрытия формы.
     */
    private fun showJobForm(existing: CronJob?) {
        val cred = store.read() ?: return
        var dialogRef: Dialog? = null
        val selectedDeliver = arrayOfNulls<String>(1)
        lateinit var nameField: EditText
        lateinit var scheduleField: EditText
        lateinit var promptField: EditText
        lateinit var deliverBox: LinearLayout
        lateinit var strip: TextView
        lateinit var save: Button
        lateinit var controls: List<View>

        dialogRef = MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, if (existing == null) "Новая задача" else "Правка: ${existing.name}", null))

            root.addView(MarsDialog.caption(this, "Имя (необязательно)"))
            nameField = MarsDialog.textField(this, "Например: Утренняя сводка", existing?.name ?: "").apply {
                minLines = 1
                maxLines = 2
            }
            root.addView(MarsDialog.block(this, nameField))

            root.addView(MarsDialog.caption(this, "Расписание"))
            scheduleField = MarsDialog.textField(this, "30m · every 2h · 0 9 * * * · in 30m", existing?.scheduleDisplay ?: "").apply {
                minLines = 1
                maxLines = 2
            }
            root.addView(MarsDialog.block(this, scheduleField))

            root.addView(MarsDialog.caption(this, "Промпт"))
            promptField = MarsDialog.textField(this, "Что сделать при запуске", existing?.prompt ?: "").apply {
                minLines = 3
                maxLines = 8
            }
            root.addView(MarsDialog.block(this, promptField))

            root.addView(MarsDialog.caption(this, "Доставка (необязательно)"))
            deliverBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            deliverBox.addView(MarsDialog.caption(this, "Загрузка опций…"))
            root.addView(MarsDialog.block(this, deliverBox))

            strip = MarsDialog.errorStrip(this)
            root.addView(MarsDialog.block(this, strip))

            save = MarsDialog.primaryButton(this, if (existing == null) "Создать" else "Сохранить") {
                submitJobForm(existing, cred, selectedDeliver[0], nameField, scheduleField, promptField, strip, controls, dialogRef)
            }
            val cancel = MarsDialog.quietButton(this, "Отмена") { dialogRef?.dismiss() }
            root.addView(MarsDialog.buttonRow(this, save, cancel))
            controls = listOf(nameField, scheduleField, promptField, save)
            controls
        }

        // Опции доставки — GET /api/crons/delivery-options (только чтение).
        lifecycleScope.launch {
            val options = withContext(Dispatchers.IO) {
                cronRepo.fetchDeliveryOptions(ChatActivity.BASE_URL, cred)
            }
            fun refresh() {
                renderDeliverPills(deliverBox, options, selectedDeliver) { value ->
                    selectedDeliver[0] = value
                    refresh()
                }
            }
            refresh()
        }
    }

    private fun renderDeliverPills(
        box: LinearLayout,
        options: List<CronDeliveryOption>,
        selected: Array<String?>,
        onSelect: (String?) -> Unit,
    ) {
        box.removeAllViews()
        if (options.isEmpty()) {
            box.addView(MarsDialog.caption(this, "Опции недоступны — сервер выберет доставку сам"))
            return
        }
        options.forEachIndexed { index, option ->
            val isSelected = selected[0] == option.value
            val pill = MarsDialog.pill(
                this,
                option.label,
                bg = if (isSelected) MarsTheme.ACCENT_BG else MarsTheme.SURFACE_SUBTLE,
                fg = if (isSelected) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_EM,
                border = if (isSelected) MarsTheme.ACCENT_BORDER else MarsTheme.BORDER_MUTED,
            ) { onSelect(if (isSelected) null else option.value) }
            box.addView(pill, LinearLayout.LayoutParams(-1, -2).apply {
                if (index > 0) topMargin = dp(6)
            })
        }
    }

    private fun submitJobForm(
        existing: CronJob?,
        cred: ru.mars.network.model.ConsumedDeviceCredential,
        deliver: String?,
        nameField: EditText,
        scheduleField: EditText,
        promptField: EditText,
        strip: TextView,
        controls: List<View>,
        dialogRef: Dialog?,
    ) {
        val prompt = promptField.text.toString()
        val schedule = scheduleField.text.toString()
        val name = nameField.text.toString()
        val validation = CronCrudPolicy.validateForm(prompt, schedule)
        if (!validation.ok) {
            MarsDialog.setError(strip, validation.promptError ?: validation.scheduleError)
            return
        }
        MarsDialog.setError(strip, null)
        MarsDialog.setResponding(controls, true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (existing == null) {
                    cronRepo.createJob(
                        ChatActivity.BASE_URL, cred,
                        prompt = prompt.trim(),
                        schedule = schedule.trim(),
                        name = name,
                        deliver = deliver,
                    )
                } else {
                    cronRepo.updateJob(
                        ChatActivity.BASE_URL, cred, existing.id,
                        prompt = prompt.trim(),
                        schedule = schedule.trim(),
                        name = name,
                        deliver = deliver,
                    )
                }
            }
            when (result) {
                is CronMutationResult.Ok -> {
                    dialogRef?.dismiss()
                    Toast.makeText(
                        this@CronActivity,
                        if (existing == null) "Задача создана" else "Задача обновлена",
                        Toast.LENGTH_SHORT,
                    ).show()
                    loadJobs()
                }
                is CronMutationResult.Failed -> {
                    MarsDialog.setResponding(controls, false)
                    MarsDialog.setError(
                        strip,
                        result.error ?: "Сервер отклонил запрос (HTTP ${result.httpCode ?: "?"})",
                    )
                }
            }
        }
    }

    // ── Удаление (с подтверждением) ────────────────────────────────────────────

    private fun confirmDelete(job: CronJob) {
        val cred = store.read() ?: return
        var dialogRef: Dialog? = null
        dialogRef = MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, "Удаление задачи", null))
            root.addView(MarsDialog.paragraph(this, CronCrudPolicy.deleteConfirmText(job.name)))
            val delete = MarsDialog.denyButton(this, "Удалить") {
                dialogRef?.dismiss()
                performDelete(job, cred)
            }
            val cancel = MarsDialog.quietButton(this, "Отмена") { dialogRef?.dismiss() }
            root.addView(MarsDialog.buttonRow(this, cancel, delete))
            listOf(delete, cancel)
        }
    }

    private fun performDelete(job: CronJob, cred: ru.mars.network.model.ConsumedDeviceCredential) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                cronRepo.deleteJob(ChatActivity.BASE_URL, cred, job.id)
            }
            when (result) {
                is CronMutationResult.Ok -> {
                    Toast.makeText(this@CronActivity, "Задача «${job.name}» удалена", Toast.LENGTH_SHORT).show()
                    loadJobs()
                }
                is CronMutationResult.Failed -> Toast.makeText(
                    this@CronActivity,
                    result.error ?: "Не удалось удалить задачу",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    // ── История прогонов и вывод ───────────────────────────────────────────────

    /** Диалог истории: GET /api/crons/history?job_id → список прогонов. */
    private fun showHistory(job: CronJob) {
        val cred = store.read() ?: return
        var dialogRef: Dialog? = null
        lateinit var body: LinearLayout
        dialogRef = MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, "История: ${job.name}", null))
            body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            body.addView(MarsDialog.paragraph(this@CronActivity, "Загрузка прогонов…"))
            root.addView(MarsDialog.block(this, body))
            val close = MarsDialog.quietButton(this, "Закрыть") { dialogRef?.dismiss() }
            root.addView(close)
            listOf(close)
        }
        lifecycleScope.launch {
            val history = withContext(Dispatchers.IO) {
                cronRepo.fetchHistory(ChatActivity.BASE_URL, cred, job.id)
            }
            body.removeAllViews()
            if (history.runs.isEmpty()) {
                body.addView(MarsDialog.paragraph(this@CronActivity, CronCrudPolicy.emptyHistoryText()))
            } else {
                body.addView(MarsDialog.caption(this@CronActivity, "Всего прогонов: ${history.total}"))
                history.runs.forEach { run ->
                    val row = MarsDialog.quietButton(this@CronActivity, CronCrudPolicy.formatRunRow(run)) {
                        showRunDetail(job, run)
                    }.apply { gravity = Gravity.START }
                    body.addView(row)
                }
            }
        }
    }

    /** Диалог одного прогона: GET /api/crons/run?job_id&filename → content/usage. */
    private fun showRunDetail(job: CronJob, run: CronRunSummary) {
        val cred = store.read() ?: return
        var dialogRef: Dialog? = null
        lateinit var body: LinearLayout
        dialogRef = MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, "Вывод прогона", null))
            body = LinearLayout(this@CronActivity).apply { orientation = LinearLayout.VERTICAL }
            body.addView(MarsDialog.paragraph(this@CronActivity, "Загрузка вывода…"))
            root.addView(MarsDialog.block(this@CronActivity, body))
            val close = MarsDialog.quietButton(this@CronActivity, "Закрыть") { dialogRef?.dismiss() }
            root.addView(close)
            listOf(close)
        }
        lifecycleScope.launch {
            val detail = withContext(Dispatchers.IO) {
                cronRepo.fetchRunDetail(ChatActivity.BASE_URL, cred, job.id, run.filename)
            }
            body.removeAllViews()
            if (detail == null) {
                body.addView(MarsDialog.paragraph(this@CronActivity, "Вывод прогона недоступен"))
                return@launch
            }
            detail.filename?.takeIf(String::isNotBlank)?.let {
                body.addView(MarsDialog.caption(this@CronActivity, it))
            }
            CronCrudPolicy.formatUsage(detail.usage).takeIf(String::isNotBlank)?.let {
                body.addView(MarsDialog.caption(this@CronActivity, it))
            }
            val output = detail.content ?: detail.snippet.orEmpty()
            if (output.isBlank()) {
                body.addView(MarsDialog.paragraph(this@CronActivity, "Пустой вывод"))
            } else {
                MarsDialog.codeBlock(this@CronActivity, null, output)?.let { body.addView(it) }
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
