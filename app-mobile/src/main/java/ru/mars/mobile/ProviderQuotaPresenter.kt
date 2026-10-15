package ru.mars.mobile

import ru.mars.network.model.ProviderQuota
import ru.mars.network.repository.ProviderQuotaOutcome
import kotlin.math.roundToInt

/** Состояние чипа квоты провайдера: загрузка / данные / не поддерживается / ошибка. */
enum class QuotaChipState { HIDDEN, LOADING, OK, UNSUPPORTED, UNAVAILABLE, ERROR }

/** Готовая к отрисовке модель чипа квоты. */
data class QuotaChipModel(
    val state: QuotaChipState,
    val visible: Boolean,
    val label: String,
    /**
     * Строка-пояснение: у неподдерживаемого провайдера — ПОЧЕМУ квоты нет
     * (сообщение сервера), у ошибки — текст сбоя. Пустая строка, если пояснять нечего.
     */
    val detail: String,
    val contentDescription: String,
    /** Есть ли смысл в кнопке повтора: у «не поддерживается» — нет. */
    val canRefresh: Boolean,
)

/**
 * Презентер чипа квоты провайдера (A10) — `GET /api/provider/quota`.
 *
 * Ключевой факт: backend отвечает 200 ВСЕГДА, поэтому «квоты нет» определяется не
 * кодом ответа, а полями [ProviderQuota.supported] и `status`. Живой ответ нашего
 * провайдера — `ok=false, supported=false, status="unsupported", quota=null`, и
 * интерфейс обязан показать это состояние текстом, а не пустотой и не выдуманными
 * нулями. Это квота АККАУНТА провайдера, не локальный бюджет контекста
 * ([ContextBudgetPolicy]) — смешивать их нельзя.
 *
 * Без Android-импортов, тестируется на JVM.
 */
class ProviderQuotaPresenter {
    var state: QuotaChipState = QuotaChipState.HIDDEN
        private set

    var quota: ProviderQuota? = null
        private set

    /** Текст ошибки транспорта (сеть/разбор), не серверное «не поддерживается». */
    var errorMessage: String? = null
        private set

    /** Чип видим, когда о квоте есть что сказать: загрузка, данные или явный отказ. */
    fun model(): QuotaChipModel {
        val current = quota
        return when (state) {
            QuotaChipState.HIDDEN -> hiddenModel()
            QuotaChipState.LOADING -> QuotaChipModel(
                state = QuotaChipState.LOADING,
                visible = true,
                label = LOADING_LABEL,
                detail = "",
                contentDescription = "Квота провайдера: загрузка",
                canRefresh = false,
            )
            QuotaChipState.OK -> okModel(current)
            QuotaChipState.UNSUPPORTED -> QuotaChipModel(
                state = QuotaChipState.UNSUPPORTED,
                visible = true,
                label = UNSUPPORTED_LABEL,
                detail = unsupportedDetail(current),
                contentDescription = unsupportedDetail(current),
                canRefresh = false,
            )
            QuotaChipState.UNAVAILABLE -> QuotaChipModel(
                state = QuotaChipState.UNAVAILABLE,
                visible = true,
                label = UNAVAILABLE_LABEL,
                detail = current?.message?.trim().orEmpty(),
                contentDescription = "Квота провайдера недоступна",
                canRefresh = true,
            )
            QuotaChipState.ERROR -> QuotaChipModel(
                state = QuotaChipState.ERROR,
                visible = true,
                label = ERROR_LABEL,
                detail = errorMessage.orEmpty(),
                contentDescription = errorMessage ?: "Квота провайдера: ошибка",
                canRefresh = true,
            )
        }
    }

    fun onLoading() {
        state = QuotaChipState.LOADING
        errorMessage = null
    }

    /**
     * Применяет payload. Порядок проверок важен: «не поддерживается» проверяется
     * ПЕРВЫМ, потому что у такого ответа `ok=false`, и наивная проверка `ok` увела
     * бы состояние в «ошибку» вместо честного «квоты у провайдера нет».
     */
    fun applyQuota(payload: ProviderQuota) {
        quota = payload
        errorMessage = null
        state = when {
            payload.isUnsupported -> QuotaChipState.UNSUPPORTED
            payload.isUnavailable -> QuotaChipState.UNAVAILABLE
            payload.quota != null -> QuotaChipState.OK
            !payload.ok -> QuotaChipState.ERROR
            // Провайдер поддержан и ответил ok, но чисел в блоке quota нет.
            // Рисовать «OK» нельзя: [model] в этом случае честно показывает
            // «Квота не прочитана». Держим state и model согласованными, иначе
            // любой, кто прочитает state вместо model(), получит ложь.
            payload.supported -> QuotaChipState.UNAVAILABLE
            else -> QuotaChipState.OK
        }
        if (state == QuotaChipState.ERROR) errorMessage = payload.message?.trim().takeUnless { it.isNullOrEmpty() }
    }

    /** Провал транспорта: чип остаётся видимым с кнопкой повтора. */
    fun onTransportError(message: String?) {
        quota = null
        errorMessage = message?.trim().takeUnless { it.isNullOrEmpty() } ?: "Не удалось получить квоту"
        state = QuotaChipState.ERROR
    }

    fun apply(outcome: ProviderQuotaOutcome) {
        when (outcome) {
            is ProviderQuotaOutcome.Success -> applyQuota(outcome.quota)
            is ProviderQuotaOutcome.Failure -> onTransportError(outcome.message)
        }
    }

    /** Смена сессии/провайдера: старые числа не должны переехать в новый контекст. */
    fun reset() {
        state = QuotaChipState.HIDDEN
        quota = null
        errorMessage = null
    }

    private fun hiddenModel() = QuotaChipModel(
        state = QuotaChipState.HIDDEN,
        visible = false,
        label = "",
        detail = "",
        contentDescription = "",
        canRefresh = false,
    )

    private fun okModel(current: ProviderQuota?): QuotaChipModel {
        val amounts = current?.quota
        if (amounts == null) {
            // Поддержано сервером, но чисел в ответе нет — честнее сказать об этом,
            // чем показать 0%.
            return QuotaChipModel(
                state = QuotaChipState.UNAVAILABLE,
                visible = true,
                label = UNAVAILABLE_LABEL,
                detail = current?.message?.trim().orEmpty(),
                contentDescription = "Квота провайдера недоступна",
                canRefresh = true,
            )
        }
        val label = current.label?.trim().takeUnless { it.isNullOrEmpty() } ?: "Квота"
        val remaining = amounts.limitRemaining
        val limit = amounts.limit
        val percent = percentRemaining(amounts.limitRemaining, amounts.limit)
        val text = when {
            remaining != null && percent != null -> "$remaining из $limit (${percent}%)"
            remaining != null -> formatAmount(remaining)
            percent != null -> "осталось ${percent}%"
            else -> label
        }
        return QuotaChipModel(
            state = QuotaChipState.OK,
            visible = true,
            label = text,
            detail = label,
            contentDescription = "$label: $text",
            canRefresh = true,
        )
    }

    companion object {
        const val LOADING_LABEL = "Квота…"
        const val UNSUPPORTED_LABEL = "Квота недоступна"
        const val UNAVAILABLE_LABEL = "Квота не прочитана"
        const val ERROR_LABEL = "Квота: ошибка"

        /**
         * Пояснение для неподдерживаемого провайдера. Приоритет — сообщение сервера
         * (оно называет провайдера и причину); если его нет, честная общая фраза.
         */
        fun unsupportedDetail(payload: ProviderQuota?): String {
            val serverMessage = payload?.message?.trim()
            if (!serverMessage.isNullOrEmpty()) return serverMessage
            val name = payload?.displayName?.trim().takeUnless { it.isNullOrEmpty() } ?: payload?.provider?.trim()
            return if (name.isNullOrEmpty()) {
                "Провайдер не отдаёт данные о квоте"
            } else {
                "Провайдер $name не отдаёт данные о квоте"
            }
        }

        /** Процент остатка; null, если считать не из чего (limit 0 или отсутствует). */
        fun percentRemaining(remaining: Double?, limit: Double?): Int? {
            if (remaining == null || limit == null || limit <= 0.0) return null
            val share = remaining / limit * 100.0
            return share.roundToInt().coerceIn(0, 100)
        }

        /** Компактное число: целые без хвоста, дробные — с одним знаком. */
        fun formatAmount(value: Double): String {
            val rounded = value.roundToInt()
            return if (kotlin.math.abs(value - rounded) < 0.05) rounded.toString()
            else ((value * 10).roundToInt() / 10.0).toString()
        }
    }
}
