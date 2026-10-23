package ru.mars.mobile

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Вкладка «Обзор» (чарт): GET /api/charts с fallback на GET /api/home через тот же
 * транспорт репозитория ([MusicChartLoader]) и офлайн-снимок при падении сети.
 *
 * Дубль D2 (`fetchHomeText` — второй, сырой HTTP-транспорт для того же эндпоинта)
 * удалён: оба запроса идут через [MusicRepository], поэтому `{success:false}` при
 * HTTP 200 не может притвориться пустым чартом.
 */

internal fun MusicActivity.loadHome() {
    job?.cancel()
    showLoading()
    job = lifecycleScope.launch {
        // Charts go through the repository: {success:false,error} at HTTP 200 is an
        // exception there, so a server rejection can never masquerade as an empty
        // «Треки не найдены» list — both endpoints failing shows the error/retry UI.
        // The /api/home fallback rides the SAME repository transport (OkHttp, shared
        // interceptors/timeouts, `success:false` → exception) instead of a second
        // hand-rolled URL connection; shelves are flattened by [MusicHomePolicy].
        val outcome = withContext(Dispatchers.IO) {
            MusicChartLoader.resolve(runCatching { repository.getCharts() }) {
                runCatching { repository.getHome() }.map(MusicHomePolicy::playbackQueue)
            }
        }
        when (outcome) {
            is MusicChartLoader.Outcome.Tracks -> {
                // Снимок пишется только из успешного ответа и только непустого:
                // кэш должен повторять то, что пользователь реально видел.
                if (outcome.items.isNotEmpty()) offlineCache.saveChartsSnapshot(outcome.items)
                showTracks(outcome.items)
            }
            is MusicChartLoader.Outcome.Failed -> showCachedCharts()
        }
    }
}

/**
 * Сеть упала на «Обзоре»: если есть годный снимок чарта — рисуем его с явной
 * подписью возраста («из кэша, снимок на 18.10 23:41») вместо экрана ошибки.
 * Нет снимка (или он протух/пуст) — прежнее поведение [showHomeError] с retry.
 */
private fun MusicActivity.showCachedCharts() {
    val decision = MusicOfflineFallbackPolicy.decide(
        items = offlineCache.getChartsSnapshot(),
        snapshotTakenAtMs = offlineCache.getTimestamp(),
        nowMs = System.currentTimeMillis(),
    )
    val cached = decision.items
    if (cached == null) {
        showHomeError()
        return
    }
    showStatus(null)
    showTracks(cached, header = offlineNotice(decision.label.orEmpty()))
}

/**
 * Подпись офлайн-снимка под заголовком списка: пилюля цвета предупреждения.
 * Обёрнута в контейнер на всю ширину, потому что [showTracks] растягивает header.
 */
internal fun MusicActivity.offlineNotice(label: String): View {
    val pill = TextView(this).apply {
        text = label
        textSize = 12f
        setTextColor(MarsTheme.STATUS_WARNING)
        background = MarsTheme.pill(this@MusicActivity, MarsTheme.SURFACE_0, strokeColor = MarsTheme.BORDER)
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(pill, LinearLayout.LayoutParams(-2, -2))
    }
}

/** Chart fetch failed (both endpoints): say so and offer an explicit retry. */
private fun MusicActivity.showHomeError() = showListError(
    message = "Чарт недоступен — проверьте сеть",
    retryDescription = "Повторить загрузку чарта",
    onRetry = { loadHome() },
)
