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
import ru.mars.network.repository.MusicAlbumSummary
import ru.mars.network.repository.MusicArtist

/**
 * Страница исполнителя: вход по тапу на имя, диалог с песнями и альбомами.
 *
 * Вынесено из [MusicActivity] (шаг 11 раскладки) как internal-расширения.
 * Два независимых запроса (`artistJob` / `albumsJob`) и их гейт остаются в хосте;
 * цикл C2 (`openArtistDialog` → `albumCard`/`openAlbumDialog` ↔ `openAlbumDialog` →
 * `artistSongRow`) сохранён намеренно: вызовы происходят «в момент тапа», порядок
 * инициализации не важен, и в одном модуле Kotlin это компилируется без интерфейса.
 */

/**
 * Тап по имени исполнителя (строка трека или мини-плеер) открывает диалог
 * исполнителя. Пока запрос в полёте, повторный тап игнорируется: второй запрос
 * ничего не добавит, а ответ всё равно перерисует тот же диалог.
 */
internal fun MusicActivity.onArtistClicked(rawName: String?) {
    val target = MusicArtistPolicy.requestTarget(rawName)
    if (target == null) {
        showStatus(MusicArtistPolicy.UNKNOWN_ARTIST)
        return
    }
    if (!MusicArtistPolicy.canStartRequest(artistJob?.isActive == true)) return
    openArtistDialog(target)
}

/**
 * Диалог исполнителя: аватар, имя, подписчики и песни из `GET /api/artist/…`
 * (песни приходят в том же ответе — второго запроса нет).
 *
 * Состояния честные: «Загружаем…» → список, либо «не найден» (404 — повтор не
 * поможет, кнопки нет), либо «проверьте сеть» + «Повторить». Повтор стартует
 * только при свободном [artistJob], поэтому серия тапов не плодит запросы.
 *
 * Воспроизведение идёт через общий плеер: тап по песне ставит весь список
 * очередью с этой песни ([MarsMusicPlayer.playTrack]), кнопка «В очередь» —
 * добавляет одну песню к текущей очереди ([MarsMusicPlayer.enqueue]).
 * Мини-плеер, вкладка «Очередь» и кнопки трека работают как раньше.
 */
internal fun MusicActivity.openArtistDialog(target: String) {
    val avatar = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = MarsTheme.card(this@openArtistDialog, MarsTheme.SURFACE_SUBTLE, radiusDp = 28)
        clipToOutline = true
        visibility = View.GONE
    }
    val nameView = TextView(this).apply {
        text = MusicArtistPolicy.initialName(target)
        textSize = 17f
        setTextColor(MarsTheme.TEXT_STRONG)
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    val subscribersView = TextView(this).apply {
        textSize = 12f
        setTextColor(MarsTheme.TEXT_MUTED)
        setPadding(0, dp(2), 0, 0)
        visibility = View.GONE
    }
    val statusView = TextView(this).apply {
        text = MusicArtistPolicy.LOADING
        textSize = 13f
        setTextColor(MarsTheme.TEXT_MUTED)
        setPadding(0, dp(12), 0, dp(10))
    }
    val songsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val retryBtn = MarsDialog.quietButton(this, MusicArtistPolicy.RETRY) {}
    retryBtn.visibility = View.GONE

    // --- секция «Альбомы»: отдельный запрос к поиску (страница исполнителя
    // альбомов не отдаёт), поэтому у неё свои состояние, ошибка и повтор.
    val albumsTitle = TextView(this).apply {
        text = MusicAlbumPolicy.SECTION_TITLE
        textSize = 13f
        setTextColor(MarsTheme.TEXT_STRONG)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(16), 0, dp(4))
        visibility = View.GONE
    }
    val albumsStatus = TextView(this).apply {
        textSize = 13f
        setTextColor(MarsTheme.TEXT_MUTED)
        setPadding(0, dp(4), 0, dp(6))
        visibility = View.GONE
    }
    val albumsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val albumsRetry = MarsDialog.quietButton(this, MusicAlbumPolicy.RETRY) {}
    albumsRetry.visibility = View.GONE

    /** Обратная связь по действию — в строке статуса диалога (за ним ничего не видно). */
    fun feedback(message: String) {
        statusView.text = message
        statusView.setTextColor(MarsTheme.ACCENT_TEXT)
    }

    fun load() {
        if (!MusicArtistPolicy.canStartRequest(artistJob?.isActive == true)) return
        statusView.text = MusicArtistPolicy.LOADING
        statusView.setTextColor(MarsTheme.TEXT_MUTED)
        retryBtn.visibility = View.GONE
        songsBox.removeAllViews()

        fun renderFailure(notFound: Boolean) {
            statusView.text = MusicArtistPolicy.failureText(notFound = notFound)
            statusView.setTextColor(MarsTheme.ACCENT_TEXT)
            retryBtn.visibility =
                if (MusicArtistPolicy.retryAvailable(notFound = notFound)) View.VISIBLE else View.GONE
        }

        fun renderArtist(artist: MusicArtist) {
            nameView.text = artist.name
            MusicArtistPolicy.subscribersLabel(artist.subscribers)?.let { label ->
                subscribersView.text = label
                subscribersView.visibility = View.VISIBLE
            }
            artist.thumbnail?.let { url ->
                avatar.visibility = View.VISIBLE
                ImageLoader.load(url, avatar)
            }
            val songs = MusicArtistPolicy.songs(artist.songs)
            if (songs.isEmpty()) {
                statusView.text = MusicArtistPolicy.EMPTY_SONGS
                statusView.setTextColor(MarsTheme.TEXT_MUTED)
                return
            }
            statusView.text = MusicArtistPolicy.songsLabel(songs.size)
            statusView.setTextColor(MarsTheme.TEXT_MUTED)
            songs.forEachIndexed { index, song ->
                songsBox.addView(
                    artistSongRow(song, index, songs, ::feedback),
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) },
                )
            }
        }

        artistJob = lifecycleScope.launch {
            // Отмена (диалог закрыли) — не «сбой сети»: пробрасываем, чтобы не
            // трогать вьюхи уже закрытого диалога.
            val artist = try {
                withContext(Dispatchers.IO) { repository.getArtist(target) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                renderFailure(notFound = false)
                return@launch
            }
            if (artist == null) renderFailure(notFound = true) else renderArtist(artist)
        }
    }

    /**
     * Альбомы исполнителя: `GET /api/search?q=<имя>&type=albums` (страница
     * исполнителя их не отдаёт — проверено на живом сервисе).
     *
     * Показ «Загружаем…» → карточки, «Альбомов нет» или «Проверьте сеть» +
     * «Повторить». Повтор гейтится тем же [MusicArtistPolicy.canStartRequest]:
     * серия тапов по «Повторить» не плодит запросы.
     *
     * [shownAlbums] помнит уже показанные карточки: повторный запрос после
     * сбоя может вернуть неполную выдачу, и список не должен «схлопнуться».
     * Порядок карточек доводит [MusicAlbumPolicy.rankForArtist]: поиск отдаёт
     * релевантную выдачу целиком, поэтому альбомы самого исполнителя идут
     * первыми, чужие — следом и не теряются.
     */
    var shownAlbums = emptyList<MusicAlbumSummary>()

    fun loadAlbums() {
        if (!MusicArtistPolicy.canStartRequest(albumsJob?.isActive == true)) return
        albumsTitle.visibility = View.VISIBLE
        albumsStatus.visibility = View.VISIBLE
        albumsStatus.text = MusicAlbumPolicy.LOADING
        albumsStatus.setTextColor(MarsTheme.TEXT_MUTED)
        albumsRetry.visibility = View.GONE
        albumsBox.removeAllViews()
        albumsJob = lifecycleScope.launch {
            val remote = try {
                withContext(Dispatchers.IO) { repository.searchAlbums(target) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                albumsStatus.text = MusicAlbumPolicy.NETWORK_FAILURE
                albumsStatus.setTextColor(MarsTheme.ACCENT_TEXT)
                albumsRetry.visibility = View.VISIBLE
                return@launch
            }
            // Поиск отдаёт всю релевантную выдачу, а не альбомы этого
            // исполнителя: свои карточки поднимаем наверх, чужие оставляем
            // следом (и не выбрасываем — иначе латиница/кириллица в имени
            // даёт «Альбомов нет» на корректной выдаче).
            shownAlbums = MusicAlbumPolicy.rankForArtist(
                MusicAlbumPolicy.mergeSummaries(remote, shownAlbums),
                target,
            )
            if (!MusicAlbumPolicy.hasAlbums(shownAlbums)) {
                albumsStatus.text = MusicAlbumPolicy.EMPTY
                albumsStatus.setTextColor(MarsTheme.TEXT_MUTED)
                return@launch
            }
            albumsStatus.visibility = View.GONE
            shownAlbums.forEach { album ->
                albumsBox.addView(
                    albumCard(album) { openAlbumDialog(album.browseId) },
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) },
                )
            }
        }
    }

    var dialogRef: Dialog? = null
    dialogRef = MarsDialog.show(this) { root ->
        root.addView(MarsDialog.header(this, MusicArtistPolicy.TITLE, null))
        val identity = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        identity.addView(avatar, LinearLayout.LayoutParams(dp(56), dp(56)))
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        names.addView(nameView)
        names.addView(subscribersView)
        identity.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(identity)
        root.addView(statusView)
        root.addView(songsBox, LinearLayout.LayoutParams(-1, -2))
        root.addView(retryBtn)
        root.addView(albumsTitle)
        root.addView(albumsStatus)
        root.addView(albumsBox, LinearLayout.LayoutParams(-1, -2))
        root.addView(albumsRetry)
        root.addView(MarsDialog.quietButton(this, MusicArtistPolicy.CLOSE) { dialogRef?.dismiss() })
        retryBtn.setOnClickListener { load() }
        albumsRetry.setOnClickListener { loadAlbums() }
        emptyList()
    }
    // Закрыли диалог — незавершённые запросы (песни и альбомы) больше не нужны.
    dialogRef?.setOnDismissListener {
        artistJob?.cancel()
        albumsJob?.cancel()
    }
    load()
    loadAlbums()
}

/**
 * Строка песни в диалоге исполнителя: обложка, название, длительность.
 * Тап по строке играет список с этой песни, кнопка — добавляет песню в очередь.
 */
internal fun MusicActivity.artistSongRow(
    track: MusicTrack,
    index: Int,
    queue: List<MusicTrack>,
    feedback: (String) -> Unit,
): View {
    val row = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(8), dp(8))
        background = MarsTheme.card(this@artistSongRow, MarsTheme.SURFACE_SUBTLE, radiusDp = 12)
        isClickable = true
        isFocusable = true
        contentDescription = "Играть: ${track.title}"
        setOnClickListener {
            MarsMusicPlayer.playTrack(index, queue)
            feedback("Играет: ${track.title}")
        }
    }
    val cover = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = MarsTheme.card(this@artistSongRow, MarsTheme.SURFACE_SUBTLE, radiusDp = 6)
        clipToOutline = true
    }
    track.thumbnail?.takeIf { it.isNotBlank() }?.let { ImageLoader.load(it, cover) }
    row.addView(cover, LinearLayout.LayoutParams(dp(40), dp(40)))

    val info = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), 0, dp(6), 0)
    }
    info.addView(TextView(this).apply {
        text = track.title
        textSize = 14f
        setTextColor(MarsTheme.TEXT_STRONG)
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    })
    info.addView(TextView(this).apply {
        text = track.duration
        textSize = 11f
        setTextColor(MarsTheme.TEXT_MUTED)
    })
    row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

    val enqueueBtn = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_playlist_add,
        "В очередь: ${track.title}",
        bg = MarsTheme.SURFACE_SUBTLE,
        iconTint = MarsTheme.TEXT_MUTED,
        sizeDp = 32,
    ).apply {
        setOnClickListener {
            MarsMusicPlayer.enqueue(track)
            feedback("Добавлено в очередь: ${track.title}")
        }
    }
    row.addView(enqueueBtn, LinearLayout.LayoutParams(dp(32), dp(32)))
    return row
}
