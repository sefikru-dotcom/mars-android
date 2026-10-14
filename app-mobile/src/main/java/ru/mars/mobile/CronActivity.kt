package ru.mars.mobile

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
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
import ru.mars.network.model.CronJob
import ru.mars.network.repository.CronRepository

class CronActivity : ComponentActivity() {

    private val store by lazy { AndroidKeyStoreCredentialStore(this) }
    private val cronRepo = CronRepository()
    private val transportFailureClassifier = TransportFailureClassifier()

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
            try {
                val jobs = withContext(Dispatchers.IO) {
                    cronRepo.fetchJobs(ChatActivity.BASE_URL, cred)
                }
                renderJobs(jobs)
            } catch (error: Exception) {
                val message = error.message.orEmpty()
                val offline = transportFailureClassifier.isTransportShaped(message)
                showState(
                    if (offline) SectionScreenState.Offline("Нет связи с сервером")
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
                setPadding(dp(14), dp(12), dp(14), dp(12))
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

            // Row 4: Action button (Run now)
            val actionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(8), 0, 0)
            }

            val runBtn = Button(this).apply {
                text = "Запустить сейчас"
                textSize = 12f
                setTextColor(Color.WHITE)
                background = MarsTheme.button(this@CronActivity, MarsTheme.ACCENT, radiusDp = 8)
                setPadding(dp(12), dp(4), dp(12), dp(4))
                setOnClickListener { runJobNow(job) }
            }
            actionRow.addView(runBtn, LinearLayout.LayoutParams(-2, dp(34)))
            card.addView(actionRow)

            jobList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    private fun runJobNow(job: CronJob) {
        val cred = store.read() ?: return
        Toast.makeText(this, "Запуск задачи: ${job.name}…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val ok = cronRepo.runJob(ChatActivity.BASE_URL, cred, job.id)
            if (ok) {
                Toast.makeText(this@CronActivity, "Задача ${job.name} запущена", Toast.LENGTH_SHORT).show()
                loadJobs()
            } else {
                Toast.makeText(this@CronActivity, "Не удалось запустить задачу", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
