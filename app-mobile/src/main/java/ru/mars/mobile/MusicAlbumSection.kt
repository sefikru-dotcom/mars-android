package ru.mars.mobile

import android.app.Dialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.model.MusicTrack
import ru.mars.network.repository.AlbumResult
import ru.mars.network.repository.MusicAlbum
import ru.mars.network.repository.MusicAlbumSummary

/**
 * Страница альбома: карточка альбома в выдаче и диалог с треками.
 *
 * Вынесено из [MusicActivity] (шаг 12 раскладки) как internal-расширения.
 * `albumJob` и гейт [MusicArtistPolicy.canStartRequest] остаются в хосте; строку
 * трека рисует общий `artistSongRow` из [MusicArtistSection] — цикл C2 оставлен
 * как есть (см. комментарий шага 11), `MusicRowFactorySection` не понадобился.
 */

/**
 * Тап по имени альбома в строке трека открывает тот же альбомный диалог, что и
 * карточка на вкладке «Обзор».
 *
 * Имя альбома без browseId (обычный случай для треков вне альбома и для выдач,
 * где сервер не смог достать id) — это подпись, а не кнопка: обработчик видит
 * пустой id и просто показывает статус, вместо того чтобы уйти в сеть за
 * заведомо отвергаемым запросом.
 */
internal fun MusicActivity.onAlbumClicked(rawBrowseId: String?) {
    val target = MusicAlbumPolicy.requestTarget(rawBrowseId)
    if (target == null) {
        showStatus(MusicAlbumPolicy.NOT_FOUND)
        return
    }
    if (!MusicArtistPolicy.canStartRequest(albumJob?.isActive == true)) return
    openAlbumDialog(target)
}

/**
 * Карточка альбома в секции «Альбомы»: обложка 48dp, название, исполнитель и год.
 *
 * Треки здесь не тянутся: карточка открывает диалог альбома, поэтому список из
 * N альбомов — это один запрос, а не N запросов за треками.
 */
internal fun MusicActivity.albumCard(album: MusicAlbumSummary, onClick: () -> Unit): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = MarsTheme.card(this@albumCard, MarsTheme.SURFACE_SUBTLE, radiusDp = 12)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        isClickable = true
        isFocusable = true
        // TalkBack читает ДЕЙСТВИЕ тапа — как у строк треков («Играть: …»).
        contentDescription = MusicAlbumPolicy.cardDescription(album)
        setOnClickListener { onClick() }
    }
    val cover = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = MarsTheme.card(this@albumCard, MarsTheme.SURFACE, radiusDp = 6)
        clipToOutline = true
    }
    album.thumbnail?.takeIf { it.isNotBlank() }?.let { ImageLoader.load(it, cover) }
    row.addView(cover, LinearLayout.LayoutParams(dp(48), dp(48)))

    val info = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), 0, 0, 0)
    }
    info.addView(TextView(this).apply {
        text = album.title
        textSize = 14f
        setTextColor(MarsTheme.TEXT_STRONG)
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    })
    val subtitle = MusicAlbumPolicy.subtitle(album)
    if (subtitle.isNotEmpty()) {
        info.addView(TextView(this).apply {
            text = subtitle
            textSize = 11f
            setTextColor(MarsTheme.TEXT_MUTED)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
    }
    row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
    return row
}

/**
 * Диалог альбома: обложка, название, исполнитель, год/число треков и треки из
 * `GET /api/album/{browseId}`.
 *
 * browseId проверяется здесь ещё раз: секция «Альбомы» берёт его из выдачи
 * поиска, но диалог — отдельная точка входа, а сервер на чужой префикс отвечает
 * кодом 500 (`must start with MPRE`). Локальная проверка не даёт показать
 * «проверьте сеть» там, где виноват id.
 *
 * Состояния честные, как у диалога исполнителя: «Загружаем…» → треки, либо
 * «Альбом не найден» (это ответ сервера, повтор не поможет), либо «проверьте
 * сеть» + «Повторить» (повтор гейтится [MusicArtistPolicy.canStartRequest], так
 * что серия тапов не плодит запросы). Тап по треку играет весь альбом с этой
 * песни ([MarsMusicPlayer.playTrack]), «В очередь» — добавляет одну
 * ([MarsMusicPlayer.enqueue]); мини-плеер и вкладка «Очередь» не затрагиваются.
 */
internal fun MusicActivity.openAlbumDialog(rawBrowseId: String) {
    val browseId = MusicAlbumPolicy.requestTarget(rawBrowseId)
    if (browseId == null) {
        showStatus(MusicAlbumPolicy.NOT_FOUND)
        return
    }
    if (!MusicArtistPolicy.canStartRequest(albumJob?.isActive == true)) return

    val cover = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = MarsTheme.card(this@openAlbumDialog, MarsTheme.SURFACE_SUBTLE, radiusDp = 8)
        clipToOutline = true
        visibility = View.GONE
    }
    val titleView = TextView(this).apply {
        textSize = 17f
        setTextColor(MarsTheme.TEXT_STRONG)
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    val artistView = TextView(this).apply {
        textSize = 12f
        setTextColor(MarsTheme.TEXT_MUTED)
        setPadding(0, dp(2), 0, 0)
        visibility = View.GONE
    }
    val metaView = TextView(this).apply {
        textSize = 12f
        setTextColor(MarsTheme.TEXT_MUTED)
        setPadding(0, dp(2), 0, 0)
        visibility = View.GONE
    }
    val statusView = TextView(this).apply {
        text = MusicAlbumPolicy.LOADING_ALBUM
        textSize = 13f
        setTextColor(MarsTheme.TEXT_MUTED)
        setPadding(0, dp(12), 0, dp(10))
    }
    val tracksBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val retryBtn = MarsDialog.quietButton(this, MusicAlbumPolicy.RETRY) {}
    retryBtn.visibility = View.GONE

    /** Обратная связь по действию — в строке статуса (за диалогом ничего не видно). */
    fun feedback(message: String) {
        statusView.visibility = View.VISIBLE
        statusView.text = message
        statusView.setTextColor(MarsTheme.ACCENT_TEXT)
    }

    fun load() {
        if (!MusicArtistPolicy.canStartRequest(albumJob?.isActive == true)) return
        statusView.visibility = View.VISIBLE
        statusView.text = MusicAlbumPolicy.LOADING_ALBUM
        statusView.setTextColor(MarsTheme.TEXT_MUTED)
        retryBtn.visibility = View.GONE
        tracksBox.removeAllViews()

        fun renderFailure(notFound: Boolean) {
            statusView.text = MusicAlbumPolicy.failureText(notFound = notFound)
            statusView.setTextColor(MarsTheme.ACCENT_TEXT)
            retryBtn.visibility =
                if (MusicAlbumPolicy.retryAvailable(notFound = notFound)) View.VISIBLE else View.GONE
        }

        fun renderAlbum(album: MusicAlbum) {
            titleView.text = album.title
            if (album.artist.isNotBlank()) {
                artistView.text = album.artist
                artistView.visibility = View.VISIBLE
            }
            val meta = MusicAlbumPolicy.meta(album.year, album.trackCount)
            if (meta.isNotEmpty()) {
                metaView.text = meta
                metaView.visibility = View.VISIBLE
            }
            album.thumbnail?.let { url ->
                cover.visibility = View.VISIBLE
                ImageLoader.load(url, cover)
            }
            if (album.tracks.isEmpty()) {
                statusView.text = MusicAlbumPolicy.EMPTY_TRACKS
                statusView.setTextColor(MarsTheme.TEXT_MUTED)
                return
            }
            statusView.visibility = View.GONE
            album.tracks.forEachIndexed { index, track ->
                // Тот же ряд, что в диалоге исполнителя: тап играет альбом с этой
                // песни, кнопка добавляет одну в очередь.
                tracksBox.addView(
                    artistSongRow(track, index, album.tracks, ::feedback),
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) },
                )
            }
        }

        albumJob = lifecycleScope.launch {
            // Отмена (диалог закрыли) — не «сбой сети»: пробрасываем, чтобы не
            // трогать вьюхи уже закрытого диалога.
            val result = try {
                withContext(Dispatchers.IO) { repository.getAlbum(browseId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                renderFailure(notFound = false)
                return@launch
            }
            when (result) {
                is AlbumResult.Loaded -> renderAlbum(result.album)
                AlbumResult.InvalidBrowseId -> renderFailure(notFound = true)
                is AlbumResult.Failed -> renderFailure(notFound = false)
            }
        }
    }

    var dialogRef: Dialog? = null
    dialogRef = MarsDialog.show(this) { root ->
        root.addView(MarsDialog.header(this, MusicAlbumPolicy.TITLE, null))
        val identity = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        identity.addView(cover, LinearLayout.LayoutParams(dp(96), dp(96)))
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        names.addView(titleView)
        names.addView(artistView)
        names.addView(metaView)
        identity.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(identity)
        root.addView(statusView)
        root.addView(tracksBox, LinearLayout.LayoutParams(-1, -2))
        root.addView(retryBtn)
        root.addView(MarsDialog.quietButton(this, MusicAlbumPolicy.CLOSE) { dialogRef?.dismiss() })
        retryBtn.setOnClickListener { load() }
        emptyList()
    }
    // Закрыли диалог — незавершённый запрос больше не нужен.
    dialogRef?.setOnDismissListener { albumJob?.cancel() }
    load()
}
