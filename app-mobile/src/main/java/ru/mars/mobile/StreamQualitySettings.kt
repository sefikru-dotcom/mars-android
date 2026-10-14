package ru.mars.mobile

import android.content.SharedPreferences

/** Persistence port for the user-selected stream profile. */
interface StreamQualityStorage {
    fun load(): StreamQuality
    fun save(quality: StreamQuality)
}

/**
 * SharedPreferences adapter for the music profile. Unknown or stale values fall
 * back to DEFAULT, which preserves the server's historical behaviour.
 */
class PrefsStreamQualityStorage(private val prefs: SharedPreferences) : StreamQualityStorage {
    override fun load(): StreamQuality =
        StreamQuality.fromQueryValue(prefs.getString(KEY_QUALITY, null))

    override fun save(quality: StreamQuality) {
        prefs.edit().putString(KEY_QUALITY, quality.queryValue).apply()
    }

    private companion object {
        const val KEY_QUALITY = "stream_quality"
    }
}

/** Persisted profile state used by the Activity and kept independent of its views. */
class StreamQualitySettings(private val storage: StreamQualityStorage) {
    var value: StreamQuality = storage.load()
        private set

    fun set(quality: StreamQuality): StreamQuality {
        value = quality
        storage.save(value)
        return value
    }
}
