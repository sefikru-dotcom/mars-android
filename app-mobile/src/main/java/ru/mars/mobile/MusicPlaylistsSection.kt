package ru.mars.mobile

import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.model.MusicTrack
import ru.mars.network.model.Playlist

/**
 * «Плейлисты»: список плейлистов, drill-down и диалоги создания/удаления/добавления.
 *
 * Вынесено из [MusicActivity] (шаг 10 раскладки) как internal-расширения.
 * Состояние drill-down (`openPlaylist`) и список плейлистов остаются полями экрана;
 * здесь живут только четыре диалога и рендер карточек. Все сетевые вызовы — в
 * [ru.mars.network.repository.MusicRepository], экран рисует то, что он вернул.
 */

/**
 * «Плейлисты»: server-side playlists of the account. The list itself is one card per
 * playlist (title, «N треков · создан …», «Открыть» and a delete button) plus a
 * «Создать плейлист» card on top; opening one drills into its tracks. All network
 * calls stay in [MusicRepository] — this screen only renders what it returns.
 */
internal fun MusicActivity.loadPlaylists() {
    libraryJob?.cancel()
    openPlaylist = null
    renderLibrary(LibraryUiState.Loading, { loadPlaylists() }) {
        playlists.forEach { playlist -> results.addView(playlistCard(playlist)) }
    }
    libraryJob = lifecycleScope.launch {
        val fetched = withContext(Dispatchers.IO) { runCatching { repository.getPlaylists() } }
        fetched.onSuccess { list ->
            playlists = list
            if (list.isNotEmpty()) offlineCache.savePlaylistsSnapshot(list)
            renderPlaylistsContent(notice = null)
        }.onFailure {
            // Сбой сети: последний снимок плейлистов лучше пустого экрана с retry.
            // Снимка нет — прежнее поведение (ошибка «Плейлисты недоступны»).
            val decision = MusicOfflineFallbackPolicy.decide(
                items = offlineCache.getPlaylistsSnapshot(),
                snapshotTakenAtMs = offlineCache.getPlaylistsTimestamp(),
                nowMs = System.currentTimeMillis(),
            )
            val cached = decision.items
            if (cached == null) {
                renderLibrary(LibraryUiState.Error("Плейлисты недоступны — проверьте сеть"), { loadPlaylists() }) {}
            } else {
                playlists = cached
                renderPlaylistsContent(notice = decision.label.orEmpty())
            }
        }
    }
}

/**
 * Ряды вкладки «Плейлисты»: заголовок со счётчиком, «Создать плейлист» и карточки.
 * [notice] — подпись офлайн-снимка; null для живых данных с сервера.
 */
internal fun MusicActivity.renderPlaylistsContent(notice: String?) {
    renderLibrary(MusicLibraryPolicy.state(false, null, playlists.size), { loadPlaylists() }) {
        notice?.let { results.addView(offlineNotice(it)) }
        headerCard(
            title = MusicLibraryPolicy.playlistCountLabel(playlists.size),
            action = "Создать плейлист",
            onAction = { promptCreatePlaylist() },
        )?.let { results.addView(it) }
        playlists.forEach { playlist -> results.addView(playlistCard(playlist)) }
    }
}

/** One playlist row: title, «N треков · создан …», an open action and a delete button. */
internal fun MusicActivity.playlistCard(playlist: Playlist): View {
    val row = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(8), dp(8))
        background = MarsTheme.card(this@playlistCard, MarsTheme.SURFACE, radiusDp = 12)
        isClickable = true
        isFocusable = true
        setOnClickListener { openPlaylistTracks(playlist) }
    }
    val info = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), 0, dp(8), 0)
    }
    info.addView(TextView(this).apply {
        text = playlist.title
        textSize = 15f
        setTextColor(MarsTheme.TEXT_STRONG)
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    })
    info.addView(TextView(this).apply {
        text = MusicLibraryPolicy.playlistSubtitle(playlist.trackCount, playlist.createdAt)
        textSize = 12f
        setTextColor(MarsTheme.TEXT_MUTED)
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    })
    row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

    val openBtn = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_play,
        "Открыть плейлист ${playlist.title}",
        bg = MarsTheme.SURFACE_SUBTLE,
        iconTint = MarsTheme.ACCENT,
        sizeDp = 34
    ).apply {
        setOnClickListener { openPlaylistTracks(playlist) }
    }
    row.addView(openBtn, LinearLayout.LayoutParams(dp(34), dp(34)))

    val deleteBtn = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_trash,
        "Удалить плейлист ${playlist.title}",
        bg = MarsTheme.SURFACE_SUBTLE,
        iconTint = MarsTheme.TEXT_MUTED,
        sizeDp = 34
    ).apply {
        setOnClickListener { confirmDeletePlaylist(playlist) }
    }
    row.addView(deleteBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6) })

    return row.also { it.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) } }
}

/** Asks for a title, then creates the playlist on the server and re-renders the list. */
internal fun MusicActivity.promptCreatePlaylist() {
    var dialogRef: Dialog? = null
    val field = MarsDialog.textField(this, hint = "Название плейлиста")
    val errorStrip = MarsDialog.errorStrip(this)
    fun submit() {
        val title = field.text?.toString().orEmpty()
        val controls = listOf(field)
        MarsDialog.setResponding(controls, true)
        MarsDialog.setError(errorStrip, null)
        lifecycleScope.launch {
            val created = withContext(Dispatchers.IO) { runCatching { repository.createPlaylist(title) } }
            MarsDialog.setResponding(controls, false)
            created.onSuccess {
                dialogRef?.dismiss()
                showStatus("Создан плейлист: ${it.title}")
                loadPlaylists()
            }.onFailure { error ->
                // «Title required» is a deliberate refusal about the title, not a network
                // fault; MusicLibraryPolicy turns it into a phrase the user can act on.
                MarsDialog.setError(errorStrip, MusicLibraryPolicy.createPlaylistError(error.message))
            }
        }
    }
    dialogRef = MarsDialog.show(this) { root ->
        root.addView(MarsDialog.header(this, "Новый плейлист", null))
        root.addView(MarsDialog.paragraph(this, "Плейлист сохраняется на сервере и доступен с любого устройства."))
        root.addView(field)
        root.addView(errorStrip)
        root.addView(
            MarsDialog.buttonRow(
                this,
                MarsDialog.secondaryButton(this, "Отмена") { dialogRef?.dismiss() },
                MarsDialog.primaryButton(this, "Создать") { submit() },
            )
        )
        listOf(field)
    }
}

/** Destructive confirmation before DELETE — a playlist may hold the only copy of a track. */
internal fun MusicActivity.confirmDeletePlaylist(playlist: Playlist) {
    var dialogRef: Dialog? = null
    dialogRef = MarsDialog.show(this) { root ->
        root.addView(MarsDialog.header(this, "Удалить плейлист", playlist.title))
        root.addView(
            MarsDialog.paragraph(
                this,
                "Плейлист «${playlist.title}» (${MusicLibraryPolicy.trackCountLabel(playlist.trackCount)}) " +
                    "будет удалён на сервере. Действие необратимо.",
            )
        )
        root.addView(
            MarsDialog.buttonRow(
                this,
                MarsDialog.secondaryButton(this, "Отмена") { dialogRef?.dismiss() },
                MarsDialog.denyButton(this, "Удалить") {
                    dialogRef?.dismiss()
                    deletePlaylist(playlist)
                },
            )
        )
        emptyList()
    }
}

internal fun MusicActivity.deletePlaylist(playlist: Playlist) {
    lifecycleScope.launch {
        val removed = withContext(Dispatchers.IO) { runCatching { repository.deletePlaylist(playlist.id) } }
        removed.onSuccess {
            showStatus("Удалён плейлист: ${playlist.title}")
            loadPlaylists()
        }.onFailure { error ->
            showStatus("Не удалось удалить: ${error.message ?: "проверьте сеть"}")
        }
    }
}

/** Drill-down: the playlist's tracks, playable like any other list. */
internal fun MusicActivity.openPlaylistTracks(playlist: Playlist) {
    libraryJob?.cancel()
    openPlaylist = playlist
    renderLibrary(LibraryUiState.Loading, { openPlaylistTracks(playlist) }) {}
    libraryJob = lifecycleScope.launch {
        val fetched = withContext(Dispatchers.IO) { runCatching { repository.getPlaylistTracks(playlist.id) } }
        fetched.onSuccess { items ->
            // showTracks renders the rows AND resets the list, so the header is handed
            // to it instead of being added first (it would be wiped otherwise).
            showTracks(
                items,
                headerCard(
                    title = "${playlist.title} · ${MusicLibraryPolicy.trackCountLabel(items.size)}",
                    action = "Плейлисты",
                    onAction = { loadPlaylists() },
                ),
                // RowAction — вложенный тип MusicActivity (в шаг 13 он переедет в MusicTrackListSection),
                // поэтому из другого файла он требует квалификации.
                action = RowAction(
                    iconRes = R.drawable.ic_trash,
                    description = "Убрать из плейлиста ${playlist.title}",
                    onTap = { track -> removeFromPlaylist(playlist, track) },
                ),
            )
        }.onFailure {
            renderLibrary(
                LibraryUiState.Error("Плейлист недоступен — проверьте сеть"),
                { openPlaylistTracks(playlist) },
            ) {}
        }
    }
}

/**
 * «Добавить в плейлист»: long-pressing any track row opens the list of server
 * playlists and the tap adds that track there (POST .../add with the video id).
 */
internal fun MusicActivity.promptAddToPlaylist(track: MusicTrack) {
    var dialogRef: Dialog? = null
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val errorStrip = MarsDialog.errorStrip(this)
    dialogRef = MarsDialog.show(this) { root ->
        root.addView(MarsDialog.header(this, "Добавить в плейлист", null))
        root.addView(
            MarsDialog.paragraph(
                this,
                "«${track.title}» — ${track.artist}",
            )
        )
        root.addView(body)
        root.addView(errorStrip)
        root.addView(MarsDialog.quietButton(this, "Закрыть") { dialogRef?.dismiss() })
        emptyList()
    }

    if (playlists.isEmpty()) {
        body.addView(
            MarsDialog.paragraph(this, "Плейлистов пока нет — создайте его в разделе «Плейлисты». Загружаем список.")
        )
    }
    lifecycleScope.launch {
        val fetched = withContext(Dispatchers.IO) { runCatching { repository.getPlaylists() } }
        body.removeAllViews()
        fetched.onSuccess { list ->
            playlists = list
            if (list.isEmpty()) {
                body.addView(MarsDialog.paragraph(this@promptAddToPlaylist, "Плейлистов пока нет — создайте его в разделе «Плейлисты»."))
                return@onSuccess
            }
            val buttons = list.map { playlist ->
                MarsDialog.secondaryButton(
                    this@promptAddToPlaylist,
                    "${playlist.title} · ${MusicLibraryPolicy.trackCountLabel(playlist.trackCount)}",
                ) {
                    addToPlaylist(playlist, track, errorStrip)
                }
            }
            buttons.forEach { body.addView(it) }
        }.onFailure {
            MarsDialog.setError(errorStrip, "Не удалось получить плейлисты — проверьте сеть")
        }
    }
}

internal fun MusicActivity.addToPlaylist(playlist: Playlist, track: MusicTrack, errorStrip: TextView) {
    MarsDialog.setError(errorStrip, null)
    lifecycleScope.launch {
        val added = withContext(Dispatchers.IO) {
            runCatching { repository.addTrackToPlaylist(playlist.id, track.id) }
        }
        added.onSuccess {
            showStatus("Добавлено в «${playlist.title}»: ${track.title}")
        }.onFailure { error ->
            MarsDialog.setError(
                errorStrip,
                MusicLibraryPolicy.playlistWriteError(error.message, fallback = "Не удалось добавить в плейлист"),
            )
        }
    }
}

/** «Убрать из плейлиста»: the drill-down row action; re-renders the opened playlist. */
internal fun MusicActivity.removeFromPlaylist(playlist: Playlist, track: MusicTrack) {
    lifecycleScope.launch {
        val removed = withContext(Dispatchers.IO) {
            runCatching { repository.removeTrackFromPlaylist(playlist.id, track.id) }
        }
        removed.onSuccess {
            showStatus("Убрано из «${playlist.title}»: ${track.title}")
            openPlaylistTracks(playlist)
        }.onFailure {
            showStatus("Не удалось убрать трек — проверьте сеть")
        }
    }
}
