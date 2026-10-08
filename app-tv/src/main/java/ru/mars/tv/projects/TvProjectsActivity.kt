package ru.mars.tv.projects

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ru.mars.tv.R
import ru.mars.tv.TvFocusLift
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TV P1 вертикальный срез проектов: живые метрики хоста и сервисов
 * (GET https://marscore.ru/dashboard/api/metrics, автообновление раз в 30 с).
 *
 * Состояния экрана: loading / error / data (host card + service cards).
 * Никакой заглушки «Онлайн»: статусы выводятся только из полученных данных
 * ([TvProjectsCardSpec]), недоступность хоста/сервисов/всего API отображается
 * явно. [R.id.statusView] — accessibility live region (polite): TalkBack
 * объявляет загрузку, обновления и ошибки. D-pad: «Назад» и «Обновить» в шапке,
 * карточки фокусируемые (TvFocusLift), в ошибке фокус переводится на «Повторить».
 *
 * Data/repository/parser слой отделён от UI: [HttpTvProjectsGateway] (транспорт),
 * [TvProjectsMetricsParser] (чистый парсинг), [TvProjectsController] (оркестрация,
 * generation guard, destroy) — покрыты JVM-тестами.
 */
class TvProjectsActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var loadingGroup: View
    private lateinit var errorGroup: View
    private lateinit var errorText: TextView
    private lateinit var retryButton: Button
    private lateinit var backButton: Button
    private lateinit var dataScroll: View
    private lateinit var cardsContainer: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var hasData = false
    private lateinit var controller: TvProjectsController

    private val refreshTick = object : Runnable {
        override fun run() {
            controller.load()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private val surface = object : TvProjectsSurface {
        override fun showLoading() {
            if (hasData) {
                // Фоновое обновление: данные остаются на экране, live region объявит статус.
                statusView.text = "Обновление…"
                return
            }
            loadingGroup.visibility = View.VISIBLE
            errorGroup.visibility = View.GONE
            dataScroll.visibility = View.GONE
            statusView.text = "Загрузка метрик…"
        }

        override fun showMetrics(metrics: TvProjectsMetrics) {
            hasData = true
            loadingGroup.visibility = View.GONE
            errorGroup.visibility = View.GONE
            dataScroll.visibility = View.VISIBLE
            render(metrics)
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
        setContentView(R.layout.activity_tv_projects)

        backButton = findViewById(R.id.backButton)
        statusView = findViewById(R.id.statusView)
        loadingGroup = findViewById(R.id.loadingGroup)
        errorGroup = findViewById(R.id.errorGroup)
        errorText = findViewById(R.id.errorText)
        retryButton = findViewById(R.id.retryButton)
        dataScroll = findViewById(R.id.dataScroll)
        cardsContainer = findViewById(R.id.cardsContainer)
        val refreshButton = findViewById<Button>(R.id.refreshButton)

        backButton.setOnClickListener { finish() }
        refreshButton.setOnClickListener { controller.load() }
        retryButton.setOnClickListener { controller.load() }

        controller = TvProjectsController(surface, HttpTvProjectsGateway(METRICS_URL))
        TvFocusLift.attachAll(backButton, refreshButton, retryButton)
        backButton.requestFocus()

        controller.load()
        handler.postDelayed(refreshTick, REFRESH_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshTick)
        if (::controller.isInitialized) controller.destroy()
        super.onDestroy()
    }

    private fun render(metrics: TvProjectsMetrics) {
        cardsContainer.removeAllViews()
        cardsContainer.addView(hostCard(metrics.host))
        if (metrics.services.isEmpty()) {
            cardsContainer.addView(servicesMissingCard())
        } else {
            metrics.services.forEach { cardsContainer.addView(serviceCard(it)) }
        }
        // Явная D-pad цепочка: «Назад» — вниз на первую карточку.
        val first = cardsContainer.getChildAt(0)
        if (first.id == View.NO_ID) first.id = View.generateViewId()
        first.nextFocusUpId = R.id.backButton
        backButton.nextFocusDownId = first.id

        statusView.text = if (metrics.timestamp.isNotBlank()) {
            "Обновлено: ${metrics.timestamp}"
        } else {
            "Обновлено в ${clock()}"
        }
    }

    private fun hostCard(host: TvHostMetrics?): View {
        val card = layoutInflater.inflate(R.layout.item_tv_host_card, cardsContainer, false)
        val dot = card.findViewById<ImageView>(R.id.hostStatusDot)
        val label = card.findViewById<TextView>(R.id.hostStatusLabel)
        val metricsText = card.findViewById<TextView>(R.id.hostMetrics)
        if (host != null) {
            dot.setImageResource(R.drawable.ic_status_dot)
            label.text = TvProjectsCardSpec.hostStatus(available = true)
            label.setTextColor(getColor(R.color.mars_status_online))
            metricsText.text = listOf(
                "RAM: ${host.ram}",
                "CPU: ${host.cpu}",
                "Диск: ${host.disk}",
                "Аптайм: ${host.uptime}",
            ).joinToString("\n")
        } else {
            dot.setImageResource(R.drawable.ic_status_dot_warning)
            label.text = TvProjectsCardSpec.hostStatus(available = false)
            label.setTextColor(getColor(R.color.mars_status_warning))
            metricsText.text = TvProjectsCardSpec.SERVICES_MISSING
        }
        TvFocusLift.attach(card)
        return card
    }

    private fun serviceCard(service: TvServiceMetrics): View {
        val card = layoutInflater.inflate(R.layout.item_tv_service_card, cardsContainer, false)
        card.findViewById<TextView>(R.id.serviceTitle).text = service.title
        val dot = card.findViewById<ImageView>(R.id.serviceStatusDot)
        val label = card.findViewById<TextView>(R.id.serviceStatusLabel)
        if (service.active) {
            dot.setImageResource(R.drawable.ic_status_dot)
            label.setTextColor(getColor(R.color.mars_status_online))
        } else {
            dot.setImageResource(R.drawable.ic_status_dot_error)
            label.setTextColor(getColor(R.color.mars_status_error))
        }
        label.text = TvProjectsCardSpec.serviceStateLabel(service.active, service.state)

        val desc = card.findViewById<TextView>(R.id.serviceDesc)
        if (service.description.isBlank()) {
            desc.visibility = View.GONE
        } else {
            desc.text = service.description
        }

        val meta = card.findViewById<TextView>(R.id.serviceMeta)
        val metaText = listOf(
            if (service.uptime.isNotBlank()) "Аптайм: ${service.uptime}" else "",
            if (service.latency.isNotBlank()) "Пинг: ${service.latency} ms" else "",
        ).filter { it.isNotBlank() }.joinToString("    ")
        if (metaText.isBlank()) {
            meta.visibility = View.GONE
        } else {
            meta.text = metaText
        }
        TvFocusLift.attach(card)
        return card
    }

    /** Секция сервисов пуста в ответе API — явная недоступность вместо выдуманного списка. */
    private fun servicesMissingCard(): View {
        val card = layoutInflater.inflate(R.layout.item_tv_service_card, cardsContainer, false)
        card.findViewById<TextView>(R.id.serviceTitle).text = "Сервисы"
        card.findViewById<ImageView>(R.id.serviceStatusDot).setImageResource(R.drawable.ic_status_dot_warning)
        val label = card.findViewById<TextView>(R.id.serviceStatusLabel)
        label.text = TvProjectsCardSpec.SERVICES_MISSING
        label.setTextColor(getColor(R.color.mars_status_warning))
        card.findViewById<TextView>(R.id.serviceDesc).visibility = View.GONE
        card.findViewById<TextView>(R.id.serviceMeta).visibility = View.GONE
        TvFocusLift.attach(card)
        return card
    }

    private fun clock() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    companion object {
        const val METRICS_URL = "https://marscore.ru/dashboard/api/metrics"
        private const val REFRESH_MS = 30_000L
    }
}
