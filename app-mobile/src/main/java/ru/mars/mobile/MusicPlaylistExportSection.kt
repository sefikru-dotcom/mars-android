package ru.mars.mobile

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.repository.MusicExportText
import java.io.File

/**
 * B3: экспорт плейлиста / избранного в m3u8 и системный шаринг.
 *
 * Точки входа для координатора (оба с необязательными параметрами по умолчанию):
 * - `sharePlaylistM3u8(playlistId)` — кнопка в карточке/меню плейлиста;
 * - `shareFavoritesM3u8()` — пункт меню избранного.
 *
 * Файл пишется в `cacheDir/exports/` — этот каталог уже открыт существующим
 * FileProvider (`@xml/file_paths`, cache-path name="exports"), authority
 * `<applicationId>.sessionexports` уже объявлен в манифесте. Манифест НЕ трогаем.
 */
internal fun MusicActivity.sharePlaylistM3u8(
    playlistId: String,
    fileName: String? = null,
) {
    exportAndShare(playlistId = playlistId, fallbackName = fileName)
}

/** Экспорт избранного (`GET /api/favorites/export?format=m3u8`). */
internal fun MusicActivity.shareFavoritesM3u8(
    fileName: String? = null,
) {
    exportAndShare(playlistId = null, fallbackName = fileName)
}

private fun MusicActivity.exportAndShare(
    playlistId: String?,
    fallbackName: String?,
) {
    showStatus(getString(R.string.music_export_preparing))
    lifecycleScope.launch {
        val fetched = withContext(Dispatchers.IO) {
            runCatching { repository.exportM3u8(playlistId) }
        }
        val payload = fetched.getOrNull()
        if (payload == null) {
            // Честная ошибка вместо вечного спиннера: сервер и offline — разные причины,
            // но текст всегда доставляется пользователю.
            val reason = fetched.exceptionOrNull()?.message
                ?: getString(R.string.music_export_failed)
            showStatus(getString(R.string.music_export_error, reason))
            return@launch
        }
        val saved = withContext(Dispatchers.IO) { payload.saveToCache(this@exportAndShare, fallbackName) }
        if (saved == null) {
            showStatus(getString(R.string.music_export_save_failed))
            return@launch
        }
        val uri = runCatching {
            FileProvider.getUriForFile(this@exportAndShare, "$packageName.sessionexports", saved)
        }.getOrNull()
        if (uri == null) {
            showStatus(getString(R.string.music_export_share_failed))
            return@launch
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/x-mpegurl"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val result = runCatching { startActivity(Intent.createChooser(intent, getString(R.string.music_export_chooser))) }
        showStatus(
            if (result.isSuccess) getString(R.string.music_export_done)
            else getString(R.string.music_export_share_failed),
        )
    }
}

/** Пишет текст в уже разрешённый FileProvider-каталог `cacheDir/exports/`. */
private fun MusicExportText.saveToCache(activity: MusicActivity, fallbackName: String?): File? {
    val dir = File(activity.cacheDir, "exports")
    if (!dir.isDirectory && !dir.mkdirs()) return null
    val safeName = (fileName.takeIf { it.isNotBlank() } ?: fallbackName ?: "mars_playlist.m3u8")
        .substringAfterLast('/')
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .takeIf { it.endsWith(".m3u8", ignoreCase = true) }
        ?: "mars_playlist.m3u8"
    val file = File(dir, safeName)
    return runCatching { file.writeText(text, Charsets.UTF_8); file }.getOrNull()
}
