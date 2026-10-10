package ru.mars.tv.cron

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ru.mars.network.api.MarsApi
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.CronJobsResponse
import ru.mars.network.repository.CronRepository
import ru.mars.tv.R
import ru.mars.tv.TvFocusLift
import ru.mars.tv.cron.TvCronPolicy.CronFilter
import ru.mars.tv.cron.TvCronPolicy.StateToken

/**
 * TV-экран «Cron» (мониторинг; паритет мобильного CronActivity в части чтения):
 * расписания планировщика и системный статус — GET /api/crons ([CronRepository]
 * в core-network), автообновление раз в 30 с. Экран read-only: запуск/пауза задач
 * остаются на мобильном клиенте, ТВ показывает факты.
 *
 * Состояния экрана: loading / error / data. `cron_unavailable`, пустой список и
 * пустая выборка фильтра различаются явно ([TvCronPolicy]); отсутствующие поля
 * (расписание, модель, сроки, последний статус) скрываются, а не заменяются
 * выдуманными заглушками. [R.id.statusView] — accessibility live region (polite).
 * D-pad: «Назад»/«Обновить» в шапке, ряд фильтров (left/right), карточки задач
 * фокусируемые ([TvFocusLift]), фокус восстанавливается после автообновления и
 * смены фильтра; в ошибке фокус переводится на «Повторить».
 *
 * Data/policy слои отделены от UI: [RepositoryTvCronGateway] (транспорт),
 * [TvCronPolicy] (чистые решения), [TvCronController] (оркестрация, generation
 * guard, destroy) — покрыты JVM-тестами.
 */
class TvCronActivity : AppCompatActivity() {

    private lateinit var store: AndroidKeyStoreCredentialStore
    private lateinit var statusView: TextView
    private lateinit var loadingGroup: View
    private lateinit var errorGroup: View
    private lateinit var errorText: TextView
    private lateinit var retryButton: Button
    private lateinit var backButton: Button
    private lateinit var dataScroll: View
    private lateinit var emptyText: TextView
    private lateinit var jobsContainer: LinearLayout
    private lateinit var chips: List<Button>

    private val handler = Handler(Looper.getMainLooper())
    private var hasData = false
    private var currentJobs: CronJobsResponse? = null
    private var filter = CronFilter.ALL
    private lateinit var controller: TvCronController

    private val refreshTick = object : Runnable {
        override fun run() {
            controller.load()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private val surface = object : TvCronSurface {
        override fun showLoading() {
            if (hasData) {
                // Фоновое обновление: список остаётся на экране, live region объявит статус.
                statusView.text = "Обновление…"
                return
            }
            loadingGroup.visibility = View.VISIBLE
            errorGroup.visibility = View.GONE
            dataScroll.visibility = View.GONE
            statusView.text = "Загрузка расписаний…"
        }

        override fun showJobs(response: CronJobsResponse) {
            hasData = true
            currentJobs = response
            loadingGroup.visibility = View.GONE
            errorGroup.visibility = View.GONE
            dataScroll.visibility = View.VISIBLE
            render()
        }

        override fun showError(message: String) {
            statusView.text = message
            if (hasData) {
                // Последние данные остаются на экране; сбой объявлен live region'ом.
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
        setContentView(R.layout.activity_tv_cron)
        store = AndroidKeyStoreCredentialStore(this)

        backButton = findViewById(R.id.backButton)
        statusView = findViewById(R.id.statusView)
        loadingGroup = findViewById(R.id.loadingGroup)
        errorGroup = findViewById(R.id.errorGroup)
        errorText = findViewById(R.id.errorText)
        retryButton = findViewById(R.id.retryButton)
        dataScroll = findViewById(R.id.dataScroll)
        emptyText = findViewById(R.id.emptyText)
        jobsContainer = findViewById(R.id.jobsContainer)
        val refreshButton = findViewById<Button>(R.id.refreshButton)
        chips = listOf(
            findViewById(R.id.filterAll),
            findViewById(R.id.filterActive),
            findViewById(R.id.filterPaused),
        )

        backButton.setOnClickListener { finish() }
        refreshButton.setOnClickListener { controller.load() }
        retryButton.setOnClickListener { controller.load() }
        chips.forEachIndexed { index, chip -> chip.setOnClickListener { selectFilter(index) } }

        controller = TvCronController(
            surface,
            RepositoryTvCronGateway(CronRepository(), MarsApi.BASE_URL) { store.read() },
        )
        TvFocusLift.attachAll(backButton, refreshButton, retryButton, *chips.toTypedArray())
        selectFilter(0)
        backButton.requestFocus()

        controller.load()
        handler.postDelayed(refreshTick, REFRESH_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshTick)
        if (::controller.isInitialized) controller.destroy()
        super.onDestroy()
    }

    /** Переключение фильтра — локальное решение ([TvCronPolicy]); перезагрузка не нужна. */
    private fun selectFilter(index: Int) {
        filter = CronFilter.entries[index]
        chips.forEachIndexed { i, chip -> chip.isSelected = i == index }
        if (hasData) render()
    }

    private fun render() {
        val response = currentJobs ?: return
        // Автообновление и смена фильтра пересобирают список: запоминаем позицию D-pad
        // фокуса и возвращаем её (clamp по [TvCronPolicy.restoreSelection], без wrap).
        val focusedIndex = jobsContainer.indexOfChild(jobsContainer.findFocus())
        jobsContainer.removeAllViews()
        statusView.text = TvCronPolicy.statusHeader(response)

        val rows = TvCronPolicy.rows(response.jobs, filter)
        if (rows.isEmpty()) {
            emptyText.text = TvCronPolicy.emptyLine(response)
            emptyText.visibility = View.VISIBLE
        } else {
            emptyText.visibility = View.GONE
        }
        rows.forEach { row -> jobsContainer.addView(jobCard(row)) }

        val first = jobsContainer.getChildAt(0)
        if (first != null) {
            if (first.id == View.NO_ID) first.id = View.generateViewId()
            // Явная D-pad цепочка: вверх из списка — на выбранный чип фильтра.
            first.nextFocusUpId = chips[filter.ordinal].id
        }
        if (focusedIndex >= 0) {
            val target = TvCronPolicy.restoreSelection(focusedIndex, jobsContainer.childCount)
            if (target != TvCronPolicy.NO_SELECTION) jobsContainer.getChildAt(target)?.requestFocus()
        }
    }

    private fun jobCard(row: TvCronPolicy.JobRow): View {
        val card = layoutInflater.inflate(R.layout.item_tv_cron_job, jobsContainer, false)
        val color = stateColor(row.token)
        card.findViewById<ImageView>(R.id.cronJobDot).setColorFilter(color)
        card.findViewById<TextView>(R.id.cronJobTitle).text = row.title
        val state = card.findViewById<TextView>(R.id.cronJobState)
        state.text = row.stateLabel
        state.setTextColor(color)
        bindOptionalLine(card.findViewById(R.id.cronJobSchedule), row.scheduleLine)
        bindOptionalLine(card.findViewById(R.id.cronJobTiming), row.timingLine)
        TvFocusLift.attach(card)
        return card
    }

    /** Отсутствующее поле скрывается целиком: строка-заглушка была бы выдуманным фактом. */
    private fun bindOptionalLine(view: TextView, text: String?) {
        if (text == null) {
            view.visibility = View.GONE
        } else {
            view.text = text
            view.visibility = View.VISIBLE
        }
    }

    /** Семантика точки: активная задача — зелёный, пауза — янтарный. */
    private fun stateColor(token: StateToken): Int = ContextCompat.getColor(
        this,
        when (token) {
            StateToken.ACTIVE -> R.color.mars_status_online
            StateToken.PAUSED -> R.color.mars_status_warning
        },
    )

    companion object {
        /** Интервал автообновления — паритет TvProjectsActivity. */
        private const val REFRESH_MS = 30_000L
    }
}
