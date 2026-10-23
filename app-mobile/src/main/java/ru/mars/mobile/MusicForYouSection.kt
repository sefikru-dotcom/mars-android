package ru.mars.mobile

import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.model.MusicShelf

/**
 * Вкладка «Для тебя»: персональная лента GET /api/home полками.
 *
 * Решения о видимости полок и о порядке очереди принимает [MusicHomePolicy]; здесь —
 * загрузка в `libraryJob` хоста и рендер полок строками треков.
 */

/**
 * «Для тебя»: персональная лента GET /api/home — полки «Продолжить слушать»,
 * «Новинки», «Для тебя», «Похожие на …» (server.py build_personal_shelf).
 * Пустая полка — нормальное состояние сервера и просто не рендерится
 * ([MusicHomePolicy]); полностью пустая лента получает понятную заглушку,
 * сбой сети — ошибку с повтором. Загрузка идёт в [libraryJob], как у остальных
 * библиотечных вкладок, и не пересекается с чартовым [job].
 */
internal fun MusicActivity.loadForYou() {
    libraryJob?.cancel()
    openPlaylist = null
    renderLibrary(LibraryUiState.Loading, { loadForYou() }) {}
    libraryJob = lifecycleScope.launch {
        val fetched = withContext(Dispatchers.IO) { runCatching { repository.getHome() } }
        fetched.onSuccess { shelves ->
            val visible = MusicHomePolicy.visibleShelves(shelves)
            if (visible.isEmpty()) {
                // Все полки пусты — это не ошибка: подсказываем, чем лента наполняется.
                tracks = emptyList()
                resetList()
                results.addView(libraryPlaceholder(getString(R.string.music_for_you_empty)))
                return@onSuccess
            }
            renderForYouShelves(visible)
        }.onFailure {
            renderLibrary(
                LibraryUiState.Error(getString(R.string.music_for_you_error)),
                { loadForYou() },
            ) {}
        }
    }
}

/**
 * Рендер полок ленты: заголовок-карточка + вертикальный список строк на каждую полку.
 * Все треки видимых полок складываются в [tracks] одной очередью в порядке рендера
 * ([MusicHomePolicy.playbackQueue]), поэтому сквозной индекс строки совпадает с
 * позицией в очереди и тап запускает воспроизведение ленты с этого трека.
 */
private fun MusicActivity.renderForYouShelves(shelves: List<MusicShelf>) {
    tracks = MusicHomePolicy.playbackQueue(shelves)
    resetList()
    var index = 0
    shelves.forEach { shelf ->
        headerCard(title = shelf.title)?.let { results.addView(it) }
        shelf.items.forEach { t ->
            results.addView(
                trackRow(t, index++),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) },
            )
        }
    }
}
