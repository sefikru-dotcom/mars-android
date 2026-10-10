package ru.mars.mobile

/**
 * Состояние баннера сетевого статуса — чистая политика видимости и текста.
 * Хост (ChatActivity) только мапит [NetworkBannerUi.tone] на цвета дизайн-системы
 * (MarsTheme.STATUS_*) и применяет видимость; решений в UI-слое не остаётся.
 */
data class NetworkBannerUi(
    val visible: Boolean,
    val text: String,
    val tone: Tone,
) {
    /** Смысловой тон баннера → цвет токена дизайн-системы на стороне хоста. */
    enum class Tone { INFO, OK, WARNING, ERROR }

    companion object {
        val Hidden = NetworkBannerUi(visible = false, text = "", tone = Tone.INFO)
    }
}

/**
 * Баннер сетевого статуса и восстановления связи поверх чата.
 *
 * Правила (состояние из [NetworkStatePolicy], очередь из [OfflineMessageQueuePolicy]):
 * - OFFLINE: красный баннер; при непустой очереди — счётчик сообщений, которые уйдут
 *   автоматически после восстановления;
 * - LOSING: жёлтый «соединение нестабильно» (handover/лимит — отправка уже буферизуется);
 * - UNVALIDATED: синий «подключение проверяется» (сеть появилась, валидация интернета идёт);
 * - ONLINE: баннер скрыт; исключение — в очереди ещё есть сообщения (идёт автоотправка):
 *   зелёный «связь восстановлена» со счётчиком, пока очередь не опустеет.
 *
 * Тексты намеренно без emoji (font-dependent rendering across OEM skins).
 */
class NetworkBannerPolicy {

    fun bannerFor(state: NetworkState, pendingCount: Int): NetworkBannerUi = when (state) {
        NetworkState.OFFLINE -> if (pendingCount > 0) {
            NetworkBannerUi(
                visible = true,
                text = "Нет связи — в очереди ${messagesLabel(pendingCount)}. Отправим автоматически",
                tone = NetworkBannerUi.Tone.ERROR,
            )
        } else {
            NetworkBannerUi(
                visible = true,
                text = "Нет связи. Сообщения будут поставлены в очередь и отправлены автоматически",
                tone = NetworkBannerUi.Tone.ERROR,
            )
        }

        NetworkState.LOSING -> NetworkBannerUi(
            visible = true,
            text = if (pendingCount > 0) {
                "Соединение нестабильно — в очереди ${messagesLabel(pendingCount)}"
            } else {
                "Соединение нестабильно…"
            },
            tone = NetworkBannerUi.Tone.WARNING,
        )

        NetworkState.UNVALIDATED -> NetworkBannerUi(
            visible = true,
            text = "Подключение проверяется…",
            tone = NetworkBannerUi.Tone.INFO,
        )

        NetworkState.ONLINE -> if (pendingCount > 0) {
            NetworkBannerUi(
                visible = true,
                text = "Связь восстановлена — отправляем ${messagesLabel(pendingCount)}…",
                tone = NetworkBannerUi.Tone.OK,
            )
        } else {
            NetworkBannerUi.Hidden
        }
    }

    /** Русская плюрализация без библиотек: 1 сообщение, 2 сообщения, 5 сообщений. */
    internal fun messagesLabel(count: Int): String {
        val form = when {
            count % 100 in 11..14 -> "сообщений"
            count % 10 == 1 -> "сообщение"
            count % 10 in 2..4 -> "сообщения"
            else -> "сообщений"
        }
        return "$count $form"
    }
}
