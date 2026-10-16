package ru.mars.tv.chat

import ru.mars.network.model.ProviderQuota
import ru.mars.network.repository.ProviderQuotaOutcome
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Состояние чипа квоты провайдера — паритет мобильного `QuotaChipState` (A10).
 * Отдельно от локального бюджета контекста в статус-строке чата: это квота АККАУНТА
 * провайдера, два источника не смешиваются.
 */
enum class TvQuotaState { HIDDEN, LOADING, OK, UNSUPPORTED, UNAVAILABLE, ERROR }

/**
 * Тон чипа — имя палитры MarsTheme, а НЕ ресурс `R.color.*`: политика обязана
 * оставаться JVM-тестируемой и не тянуть Android-framework. Экран переводит тон в
 * конкретный цвет темы, а прозаические строки — в ресурсы: в этом файле нет ни
 * цветов, ни пользовательских фраз.
 */
enum class TvQuotaTone { NEUTRAL, ONLINE, WARNING, ALERT }

/** Какие числа реально есть в ответе: от этого зависит фраза подписи (порядок — как в mobile). */
enum class TvQuotaAmountsShape { REMAINING_OF_LIMIT, REMAINING_ONLY, PERCENT_ONLY, NONE }

/**
 * Готовая к отрисовке модель чипа квоты. Подпись и пояснение собирает экран из
 * ресурсов, поэтому здесь только решения (видимость, тон, форма чисел) и данные.
 */
data class TvQuotaChip(
    val state: TvQuotaState,
    val visible: Boolean,
    val tone: TvQuotaTone,
    /** Есть ли смысл в повторе по нажатию: у «провайдер не поддерживает квоту» — нет. */
    val canRefresh: Boolean,
    val amountsShape: TvQuotaAmountsShape,
    /** Имя провайдера из ответа (display_name, иначе provider); null — сервер его не назвал. */
    val providerName: String?,
    /** Сообщение сервера/сбоя: ПОЧЕМУ квоты нет. null, если пояснять нечего. */
    val note: String?,
    val remaining: Double?,
    val limit: Double?,
    val percentRemaining: Int?,
)

/**
 * Политика чипа квоты провайдера на ТВ — `GET /api/provider/quota`
 * ([ru.mars.network.repository.SessionRepository.getProviderQuota]).
 *
 * Ключевой факт ручки: она отвечает 200 ВСЕГДА, поэтому «квоты нет» определяется не
 * кодом ответа, а полями [ProviderQuota.supported] и `status`. Живой ответ нашего
 * провайдера — `ok=false, supported=false, status="unsupported", quota=null`, и такой
 * ответ обязан показываться словами, а не пустотой и не выдуманными нулями
 * ([ProviderQuota.isUnsupported] проверяется ПЕРВЫМ: наивная проверка `ok` увела бы живой кейс в
 * «ошибку»). [ProviderQuotaOutcome.Failure] означает только то, что запрос не дошёл
 * или тело не разобралось — это [TvQuotaState.ERROR] с честным повтором.
 *
 * Контракт сверен с мобильным `ProviderQuotaPresenter` (A10) один в один по состояниям;
 * отличие ТВ-версии — тон палитры по остатку (пороги ниже) и отсутствие прозы/Android.
 * Логика чистого решения: без вьюх, без сети, тестируется на JVM.
 */
class TvQuotaPolicy {

    var state: TvQuotaState = TvQuotaState.HIDDEN
        private set

    var quota: ProviderQuota? = null
        private set

    /** Текст сбоя транспорта (сеть/разбор), а не серверное «не поддерживается». */
    var errorMessage: String? = null
        private set

    /** Чип видим, когда о квоте есть что сказать: загрузка, данные или явный отказ. */
    fun model(): TvQuotaChip {
        val current = quota
        return when (state) {
            TvQuotaState.HIDDEN -> TvQuotaChip(
                state = TvQuotaState.HIDDEN,
                visible = false,
                tone = TvQuotaTone.NEUTRAL,
                canRefresh = false,
                amountsShape = TvQuotaAmountsShape.NONE,
                providerName = null,
                note = null,
                remaining = null,
                limit = null,
                percentRemaining = null,
            )
            TvQuotaState.LOADING -> base(TvQuotaState.LOADING, TvQuotaTone.NEUTRAL, canRefresh = false, current)
            TvQuotaState.OK -> okModel(current)
            TvQuotaState.UNSUPPORTED -> base(
                TvQuotaState.UNSUPPORTED, TvQuotaTone.NEUTRAL, canRefresh = false, current,
                note = current?.message?.trim().takeUnless { it.isNullOrEmpty() },
            )
            TvQuotaState.UNAVAILABLE -> base(
                TvQuotaState.UNAVAILABLE, TvQuotaTone.WARNING, canRefresh = true, current,
                note = current?.message?.trim().takeUnless { it.isNullOrEmpty() },
            )
            TvQuotaState.ERROR -> base(
                TvQuotaState.ERROR, TvQuotaTone.ALERT, canRefresh = true, null,
                note = errorMessage,
            )
        }
    }

    fun onLoading() {
        state = TvQuotaState.LOADING
        errorMessage = null
    }

    /**
     * Применяет payload. Порядок проверок важен и повторяет mobile: «не поддерживается»
     * первым, затем «поддержано, но сейчас не читается», затем наличие чисел. Пустой
     * блок quota (`quota != null`, но оба числа null) остаётся [TvQuotaState.OK] с формой
     * [TvQuotaAmountsShape.NONE]: [model] покажет отсутствие чисел, а не ноль.
     */
    fun applyQuota(payload: ProviderQuota) {
        quota = payload
        errorMessage = null
        state = when {
            payload.isUnsupported -> TvQuotaState.UNSUPPORTED
            payload.isUnavailable -> TvQuotaState.UNAVAILABLE
            payload.quota != null -> TvQuotaState.OK
            !payload.ok -> TvQuotaState.ERROR
            // Провайдер поддержан и ответил ok, но блока quota в теле нет: рисовать «OK»
            // нельзя — чисел не существует, состояние остаётся «не прочитана».
            payload.supported -> TvQuotaState.UNAVAILABLE
            else -> TvQuotaState.OK
        }
        if (state == TvQuotaState.ERROR) {
            errorMessage = payload.message?.trim().takeUnless { it.isNullOrEmpty() }
        }
    }

    /** Провал транспорта: чип остаётся видимым с повтором; пустое сообщение — общая фраза экрана. */
    fun onTransportError(message: String?) {
        quota = null
        errorMessage = message?.trim().takeUnless { it.isNullOrEmpty() }
        state = TvQuotaState.ERROR
    }

    fun apply(outcome: ProviderQuotaOutcome) {
        when (outcome) {
            is ProviderQuotaOutcome.Success -> applyQuota(outcome.quota)
            is ProviderQuotaOutcome.Failure -> onTransportError(outcome.message)
        }
    }

    /**
     * Нет авторизации / смена контекста: старые числа не должны переехать в новый ответ.
     * Только чтение — политика ничего не пишет и не мутирует квоту на сервере.
     */
    fun reset() {
        state = TvQuotaState.HIDDEN
        quota = null
        errorMessage = null
    }

    /** Общая часть моделей: видимость и данные — одинаковые, различаются тон и право на повтор. */
    private fun base(
        state: TvQuotaState,
        tone: TvQuotaTone,
        canRefresh: Boolean,
        current: ProviderQuota?,
        note: String? = null,
    ): TvQuotaChip {
        val amounts = current?.quota
        val remaining = amounts?.limitRemaining
        val limit = amounts?.limit
        val percent = percentRemaining(remaining, limit)
        return TvQuotaChip(
            state = state,
            visible = true,
            tone = tone,
            canRefresh = canRefresh,
            amountsShape = amountsShape(remaining, limit, percent),
            providerName = providerName(current),
            note = note,
            remaining = remaining,
            limit = limit,
            percentRemaining = percent,
        )
    }

    /**
     * Данные есть: тон считается по ОСТАТКУ (а не по израсходованному). Пороги —
     * те же две границы, что у пилюли контекста в статус-строке (50/75), только
     * зеркально: там растёт расход, здесь падает остаток. Без процента (нет limit)
     * тон нейтральный — тревогу из неизвестного числа не выдумываем.
     */
    private fun okModel(current: ProviderQuota?): TvQuotaChip {
        val amounts = current?.quota
        if (amounts == null) {
            // Поддержано сервером, но блока чисел в ответе нет — честнее сказать «не прочитана»,
            // чем показать 0% (mobile parity, состояние остаётся согласованным с model()).
            return base(
                TvQuotaState.UNAVAILABLE, TvQuotaTone.WARNING, canRefresh = true, current,
                note = current?.message?.trim().takeUnless { it.isNullOrEmpty() },
            )
        }
        val percent = percentRemaining(amounts.limitRemaining, amounts.limit)
        return base(TvQuotaState.OK, toneFor(percent), canRefresh = true, current)
    }

    companion object {
        /** Ниже этого остатка (строго) тон становится предупреждающим. */
        const val WARN_BELOW_PERCENT = 75

        /** Ниже этого остатка (строго) тон становится тревожным. */
        const val ALERT_BELOW_PERCENT = 50

        /**
         * Тон по проценту остатка. Границы включительные в «хорошую» сторону:
         * ровно 75 — ещё ONLINE, ровно 50 — ещё WARNING. null (нечего считать) — NEUTRAL.
         */
        fun toneFor(percentRemaining: Int?): TvQuotaTone = when {
            percentRemaining == null -> TvQuotaTone.NEUTRAL
            percentRemaining >= WARN_BELOW_PERCENT -> TvQuotaTone.ONLINE
            percentRemaining >= ALERT_BELOW_PERCENT -> TvQuotaTone.WARNING
            else -> TvQuotaTone.ALERT
        }

        /** Процент остатка; null, если считать не из чего (нет limit или limit <= 0). */
        fun percentRemaining(remaining: Double?, limit: Double?): Int? {
            if (remaining == null || limit == null || limit <= 0.0) return null
            val share = remaining / limit * 100.0
            return share.roundToInt().coerceIn(0, 100)
        }

        /** Форма подписи — порядок проверок как в mobile `okModel`. */
        fun amountsShape(remaining: Double?, limit: Double?, percent: Int?): TvQuotaAmountsShape = when {
            remaining != null && limit != null && percent != null -> TvQuotaAmountsShape.REMAINING_OF_LIMIT
            remaining != null -> TvQuotaAmountsShape.REMAINING_ONLY
            percent != null -> TvQuotaAmountsShape.PERCENT_ONLY
            else -> TvQuotaAmountsShape.NONE
        }

        /** Имя провайдера: display_name приоритетнее технического provider. */
        fun providerName(payload: ProviderQuota?): String? =
            payload?.displayName?.trim().takeUnless { it.isNullOrEmpty() }
                ?: payload?.provider?.trim().takeUnless { it.isNullOrEmpty() }

        /** Компактное число: целые без хвоста, дробные — с одним знаком (mobile parity). */
        fun formatAmount(value: Double): String {
            val rounded = value.roundToInt()
            return if (abs(value - rounded) < 0.05) rounded.toString()
            else ((value * 10).roundToInt() / 10.0).toString()
        }
    }
}
