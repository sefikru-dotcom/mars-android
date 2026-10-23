package ru.mars.mobile

import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.ImageButton
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.mobile.MusicActivity.MusicTab
import ru.mars.network.model.MusicTrack

/**
 * Вкладка «Избранное»: список лайкнутых треков и лайк/анлайк с любой строки экрана.
 *
 * Идентификаторы живут в хосте (`favoriteIds`, `heartButtons` — их видят и чарт, и
 * поиск, и очередь); здесь — загрузка списка, префетч идентификаторов, перекраска
 * сердечек и сам запрос лайка.
 */

internal fun MusicActivity.loadFavorites() {
    job = lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { repository.getFavorites() } }
        result.onSuccess { favs ->
            favoriteIds.clear()
            favoriteIds += favs.map { it.id }
            if (favs.isEmpty()) {
                tracks = emptyList()
                resetList()
                results.addView(TextView(this@loadFavorites).apply {
                    text = "В избранном пусто — нажмите сердечко на треке"
                    setTextColor(MarsTheme.TEXT_MUTED)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(32), 0, dp(32))
                })
            } else {
                showTracks(favs)
            }
        }.onFailure {
            tracks = emptyList()
            resetList()
            showStatus("Избранное недоступно")
        }
    }
}

/** Prefetches favorite ids so hearts render filled on the charts/search rows. */
internal fun MusicActivity.refreshFavoriteIds() {
    lifecycleScope.launch {
        val favs = withContext(Dispatchers.IO) { runCatching { repository.getFavorites() }.getOrNull() }
            ?: return@launch
        favoriteIds.clear()
        favoriteIds += favs.map { it.id }
        favoriteIds.toList().forEach { refreshHeart(it) }
    }
}

private fun MusicActivity.refreshHeart(id: String) {
    val filled = favoriteIds.contains(id)
    heartButtons[id]?.forEach { button ->
        button.setImageResource(if (filled) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
        button.imageTintList = ColorStateList.valueOf(if (filled) MarsTheme.ACCENT else MarsTheme.TEXT_MUTED)
        // MUS-M3: the heart announces the ACTION the tap performs on this track
        button.contentDescription = MusicA11yPolicy.heartAction(filled)
    }
}

internal fun MusicActivity.onHeartClicked(track: MusicTrack, button: ImageButton) {
    button.isEnabled = false // debounce double taps while the request is in flight
    lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching { repository.toggleFavorite(track.id, track) }.getOrNull()
        }
        button.isEnabled = true
        when (result) {
            true -> {
                favoriteIds += track.id
                refreshHeart(track.id)
                showStatus("Добавлено в избранное: ${track.title}")
            }
            false -> {
                favoriteIds -= track.id
                refreshHeart(track.id)
                showStatus("Удалено из избранного: ${track.title}")
                if (currentTab == MusicTab.FAVORITES) {
                    val rest = tracks.filterNot { it.id == track.id }
                    if (rest.isEmpty()) loadFavorites() else showTracks(rest)
                }
            }
            null -> showStatus("Избранное недоступно")
        }
    }
}
