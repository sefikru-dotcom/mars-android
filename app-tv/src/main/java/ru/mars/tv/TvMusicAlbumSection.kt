package ru.mars.tv

import android.app.Dialog
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import ru.mars.network.repository.MusicAlbum
import ru.mars.network.model.MusicTrack

/**
 * Секция «Диалог альбома» ТВ-экрана «Музыка».
 *
 * Вынесено из [TvMusicActivity] (декомпозиция хоста, W3) как extension-функции на Activity.
 * Переход «трек -> альбом» с пульта: диалог `dialog_tv_album`, состояния [TvAlbumFlow]
 * (Loading -> Loaded/NotFound/Error, generation-guard), выбор трека ставит очередь из ВСЕГО
 * альбома ([TvAlbumPolicy.playRequest]) — паритет с мобильным клиентом.
 *
 * Состояние — открытый [TvMusicActivity.albumDialog] и сам автомат — остаётся в хосте.
 */

/**
 * Диалог альбома на TV — минимальный вариант брифа: полноценный экран не нужен,
 * но переход «трек -> альбом» обязан работать с пульта.
 *
 * Запрос идёт через [albumFlow] (чистый автомат: Loading -> Loaded/NotFound/Error,
 * generation-guard), Android здесь только рисует. Диалог открывается СРАЗУ с
 * заголовком и состоянием загрузки — на TV ожидание пустого экрана читается как
 * «пульт не работает». Фокус стартует на «Закрыть»: список треков приедет позже,
 * и окно без фокуса залипло бы до ответа сервера ([TvMusicFocusPolicy]).
 */
internal fun TvMusicActivity.openAlbumDialog(rawBrowseId: String?) {
    val browseId = TvAlbumPolicy.requestTarget(rawBrowseId) ?: return
    albumDialog?.takeIf { it.isShowing }?.dismiss()
    val dialog = Dialog(this)
    dialog.setContentView(R.layout.dialog_tv_album)
    dialog.findViewById<TextView>(R.id.albumTitleText).text = getString(R.string.tv_album_title)
    val closeButton = dialog.findViewById<Button>(R.id.albumCloseButton)
    val retryButton = dialog.findViewById<Button>(R.id.albumRetryButton)
    closeButton.setOnClickListener { dialog.dismiss() }
    retryButton.setOnClickListener { albumFlow.retry() }
    TvFocusLift.attach(closeButton, reducedMotion)
    TvFocusLift.attach(retryButton, reducedMotion)
    // Закрытие окна гасит запрос в пути: ответ для закрытого диалога не нужен, а
    // следующее открытие обязано начать с чистого состояния.
    dialog.setOnDismissListener {
        albumFlow.close()
        albumDialog = null
    }
    dialog.window?.apply {
        setLayout(dp(760), dp(640))
        setBackgroundDrawableResource(android.R.color.transparent)
    }
    albumDialog = dialog
    dialog.show()
    closeButton.requestFocus()
    albumFlow.open(browseId)
}

/**
 * Рендер состояния [albumFlow] в открытый диалог (только UI-поток).
 *
 * Список треков пересобирается на каждый Loaded: он приходит одним ответом, поэтому
 * инкрементального обновления не требуется. Каждая строка фокусируема и играет ВЕСЬ
 * альбом с этой позиции ([TvAlbumPolicy.playRequest]) — паритет с mobile, где трек
 * альбома ставит очередь из альбома, а не одиночный трек.
 */
internal fun TvMusicActivity.renderAlbumPanel(state: TvAlbumPanelState) {
    val dialog = albumDialog?.takeIf { it.isShowing } ?: return
    val title = dialog.findViewById<TextView>(R.id.albumTitleText)
    val meta = dialog.findViewById<TextView>(R.id.albumMetaText)
    val stateText = dialog.findViewById<TextView>(R.id.albumStateText)
    val list = dialog.findViewById<LinearLayout>(R.id.albumTrackList)
    val scroll = dialog.findViewById<ScrollView>(R.id.albumScroll)
    val retry = dialog.findViewById<Button>(R.id.albumRetryButton)
    list.removeAllViews()
    // Любой новый кадр начинается с «нет кнопки повтора»: она возвращается только
    // сетевому отказу ([TvAlbumPolicy.retryVisible]) — у «не найден» повтор мёртв.
    retry.visibility = View.GONE
    when (state) {
        TvAlbumPanelState.Idle -> Unit

        is TvAlbumPanelState.Loading -> {
            title.text = getString(R.string.tv_album_title)
            meta.visibility = View.GONE
            stateText.text = TvAlbumPolicy.LOADING
            stateText.visibility = View.VISIBLE
            scroll.visibility = View.GONE
        }

        is TvAlbumPanelState.Loaded -> {
            val album = state.album
            title.text = album.title.ifBlank { getString(R.string.tv_album_title) }
            val metaLine = TvAlbumPolicy.dialogMeta(album)
            meta.text = metaLine
            meta.visibility = if (metaLine.isEmpty()) View.GONE else View.VISIBLE
            if (album.tracks.isEmpty()) {
                stateText.text = TvAlbumPolicy.EMPTY
                stateText.visibility = View.VISIBLE
                scroll.visibility = View.GONE
            } else {
                stateText.visibility = View.GONE
                scroll.visibility = View.VISIBLE
                album.tracks.forEachIndexed { index, track ->
                    list.addView(
                        albumTrackRow(album, track, index),
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(64),
                        ).apply { bottomMargin = dp(8) },
                    )
                }
                // Фокус уже на «Закрыть» — список доступен стрелкой UP, а не
                // принудительным переносом (иначе фокус прыгал бы при каждом рендере).
            }
        }

        is TvAlbumPanelState.NotFound -> {
            meta.visibility = View.GONE
            stateText.text = TvAlbumPolicy.failureText(notFound = true)
            stateText.visibility = View.VISIBLE
            scroll.visibility = View.GONE
            retry.visibility = if (TvAlbumPolicy.retryVisible(notFound = true)) View.VISIBLE else View.GONE
        }

        is TvAlbumPanelState.Error -> {
            meta.visibility = View.GONE
            stateText.text = TvAlbumPolicy.failureText(notFound = false)
            stateText.visibility = View.VISIBLE
            scroll.visibility = View.GONE
            retry.visibility = if (TvAlbumPolicy.retryVisible(notFound = false)) View.VISIBLE else View.GONE
        }
    }
}

/** Строка трека внутри диалога альбома: номер, название, длительность, фокус D-pad. */
internal fun TvMusicActivity.albumTrackRow(album: MusicAlbum, track: MusicTrack, index: Int): View {
    val label = TextView(this).apply {
        text = "${index + 1}. ${TvAlbumPolicy.trackLine(track)}"
        tvTextSize(R.dimen.tv_text_body)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), 0, dp(20), 0)
        setTextColor(ContextCompat.getColor(context, R.color.mars_text))
        setBackgroundResource(R.drawable.bg_focus_card)
        isFocusable = true
        isClickable = true
        contentDescription = getString(R.string.tv_album_play_track, track.title)
        setOnClickListener {
            val request = TvAlbumPolicy.playRequest(album, index) ?: return@setOnClickListener
            TvMusicPlayer.playPlaylist(request.tracks, request.startIndex)
        }
    }
    TvFocusLift.attach(label, reducedMotion)
    return label
}
