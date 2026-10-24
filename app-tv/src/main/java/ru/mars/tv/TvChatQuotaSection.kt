package ru.mars.tv

import android.view.View
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ru.mars.network.repository.ProviderQuotaOutcome
import ru.mars.network.repository.SessionRepository
import ru.mars.tv.chat.TvQuotaAmountsShape
import ru.mars.tv.chat.TvQuotaChip
import ru.mars.tv.chat.TvQuotaPolicy
import ru.mars.tv.chat.TvQuotaState
import ru.mars.tv.chat.TvQuotaTone

/**
 * Секция «Квота провайдера» экрана ТВ-чата.
 *
 * Вынесено из [TvChatActivity] (декомпозиция хоста, W2).
 *
 * Чип квоты рядом с моделью: сколько осталось и когда обновится — только живые числа
 * аккаунта (GET провайдера через [ProviderQuotaOutcome]); сбой сети чип не роняет, а
 * даёт доступный повтор. Тон и формат чисел — в [TvQuotaChip]/[TvQuotaPolicy].
 *
 * Форма переноса: extension-функции на Activity — состояние (View-поля, биндинг)
 * остаётся в хосте, тела перенесены дословно.
 */

/**
 * Чип квоты провайдера в статус-строке ([TvQuotaPolicy]): тон политики переводится
 * в палитру MarsTheme, проза — из ресурсов (экран не собирает текст сам). Чип
 * интерактивен РОВНО при `canRefresh`: там, где повтор бессмыслен («провайдер не
 * отдаёт квоту»), он остаётся непроходимым для D-pad, а не рисует ложную кнопку.
 */
internal fun TvChatActivity.renderQuotaChip() {
    if (!quotaChipReady) return
    val model = quotaPolicy.model()
    // Уход чипа (скрытие или потеря интерактива) не имеет права оставить D-pad на
    // исчезающем элементе — тот же приём, что в [renderReasoningChip].
    if ((!model.visible || !model.canRefresh) && quotaChip.hasFocus() && modelButtonReady) {
        modelButton.requestFocus()
    }
    quotaChip.visibility = if (model.visible) View.VISIBLE else View.GONE
    quotaChip.text = quotaLabel(model)
    quotaChip.contentDescription = quotaDescription(model)
    quotaChip.isFocusable = model.canRefresh
    quotaChip.isClickable = model.canRefresh
    quotaChip.setBackgroundResource(if (model.canRefresh) R.drawable.bg_focus_chip else R.drawable.bg_chip)
    quotaChip.setTextColor(ContextCompat.getColor(this, when (model.tone) {
        TvQuotaTone.ONLINE -> R.color.mars_status_online
        TvQuotaTone.WARNING -> R.color.mars_status_warning
        TvQuotaTone.ALERT -> R.color.mars_status_error
        TvQuotaTone.NEUTRAL -> R.color.mars_text_em
    }))
}

/** Подпись чипа: числа форматирует политика (JVM-тест), проза — из ресурсов. */
internal fun TvChatActivity.quotaLabel(model: TvQuotaChip): String = when (model.state) {
    TvQuotaState.HIDDEN -> ""
    TvQuotaState.LOADING -> getString(R.string.tv_chip_quota_loading)
    TvQuotaState.UNSUPPORTED -> getString(R.string.tv_chip_quota_unsupported)
    TvQuotaState.UNAVAILABLE -> getString(R.string.tv_chip_quota_unread)
    TvQuotaState.ERROR -> getString(R.string.tv_chip_quota_error)
    TvQuotaState.OK -> quotaAmountsLabel(model)
}

/** Числовая подпись успеха; выдуманных нулей нет — нечего показать, значит «Квота». */
internal fun TvChatActivity.quotaAmountsLabel(model: TvQuotaChip): String {
    val remaining = model.remaining
    val limit = model.limit
    val percent = model.percentRemaining
    return when (model.amountsShape) {
        TvQuotaAmountsShape.REMAINING_OF_LIMIT ->
            if (remaining != null && limit != null && percent != null) {
                getString(
                    R.string.tv_chip_quota_of,
                    TvQuotaPolicy.formatAmount(remaining),
                    TvQuotaPolicy.formatAmount(limit),
                    percent,
                )
            } else {
                getString(R.string.tv_chip_quota_default)
            }
        TvQuotaAmountsShape.REMAINING_ONLY ->
            remaining?.let { TvQuotaPolicy.formatAmount(it) } ?: getString(R.string.tv_chip_quota_default)
        TvQuotaAmountsShape.PERCENT_ONLY ->
            percent?.let { getString(R.string.tv_chip_quota_left, it) } ?: getString(R.string.tv_chip_quota_default)
        TvQuotaAmountsShape.NONE -> getString(R.string.tv_chip_quota_default)
    }
}

/**
 * Пояснение для фокуса/озвучки. У «не поддерживается» причина — сообщение сервера,
 * иначе честная фраза с именем провайдера; у сбоя — текст сети (или общая фраза).
 * Повторяемое состояние дописывает подсказку о нажатии — кнопка не бывает немой.
 */
internal fun TvChatActivity.quotaDescription(model: TvQuotaChip): String {
    val base = when (model.state) {
        TvQuotaState.HIDDEN -> ""
        TvQuotaState.LOADING -> getString(R.string.tv_chip_quota_loading_desc)
        TvQuotaState.UNSUPPORTED -> model.note ?: run {
            val name = model.providerName
            if (name.isNullOrBlank()) getString(R.string.tv_chip_quota_no_data)
            else getString(R.string.tv_chip_quota_no_data_provider, name)
        }
        TvQuotaState.UNAVAILABLE -> getString(R.string.tv_chip_quota_unread_desc)
        TvQuotaState.ERROR -> model.note ?: getString(R.string.tv_chip_quota_error_desc)
        TvQuotaState.OK -> getString(R.string.tv_chip_quota_ok_desc, quotaLabel(model))
    }
    return if (model.canRefresh && base.isNotEmpty()) {
        getString(R.string.tv_chip_quota_retry_desc, base)
    } else {
        base
    }
}

/**
 * Читает квоту активного провайдера (mobile A10 parity): при открытии диалога и при
 * перезагрузке истории — в остальных состояниях провайдер неизвестен, а числа
 * относятся к АККАУНТУ, не к диалогу. Сбой сети не роняет экран: чип переходит в
 * ERROR с доступным повтором. Ручка только читающая — квота не пишется и не мутируется.
 */
internal fun TvChatActivity.refreshProviderQuota() {
    val credential = store.read() ?: run {
        quotaPolicy.reset()
        renderQuotaChip()
        return
    }
    quotaPolicy.onLoading()
    renderQuotaChip()
    lifecycleScope.launch {
        val outcome = try {
            SessionRepository().getProviderQuota(TvChatActivity.BASE_URL, credential)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Пустое сообщение — не повод сочинять текст: общую фразу даст ресурс.
            ProviderQuotaOutcome.Failure(error.message ?: "")
        }
        quotaPolicy.apply(outcome)
        renderQuotaChip()
    }
}
