package ru.mars.mobile

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
                showTracks(favs, header = favoritesShareRow())
            }
        }.onFailure {
            tracks = emptyList()
            resetList()
            showStatus("Избранное недоступно")
        }
    }
}

/**
 * Строка «Поделиться избранным» над списком: экспорт в m3u8 через системный шаринг
 * ([shareFavoritesM3u8]). Показывается только при непустом избранном — делиться нечем
 * значит и кнопка не нужна.
 */
internal fun MusicActivity.favoritesShareRow(): View = TextView(this).apply {
    text = getString(R.string.music_favorites_share)
    textSize = 13f
    setTextColor(MarsTheme.ACCENT_TEXT)
    typeface = android.graphics.Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    setPadding(dp(14), dp(10), dp(14), dp(10))
    background = MarsTheme.button(this@favoritesShareRow, MarsTheme.ACCENT_BG, 10, MarsTheme.ACCENT)
    isClickable = true
    isFocusable = true
    contentDescription = getString(R.string.music_favorites_share)
    setOnClickListener { shareFavoritesM3u8() }
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
