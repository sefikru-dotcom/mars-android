package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression pin for the mobile engine swap (ru.mars.mobile, Media3 migration): the
 * process-scoped music transport must hold NO android.media.MediaPlayer. android.media
 * MediaPlayer routes AAC streams into AudioFlinger's compressed-offload branch on real
 * devices («Offload request, forcing to Direct Output», frozen «Frames written») where the
 * output goes silent; the replacement is Media3 ExoPlayer 1.2.1 (app-tv/TvMusicPlayer
 * parity), which has no offload route at all: software decode to PCM over a regular
 * AudioTrack. Mirrors app-tv's TvMusicPlayerNoMediaPlayerTest and guards the field surface
 * reflectively (pure JVM — `::class.java` does not trigger the object's <clinit>), so a
 * future refactor cannot silently re-introduce the offload-prone engine.
 */
class MarsMusicPlayerNoMediaPlayerTest {

    @Test
    fun `transport declares no android MediaPlayer field`() {
        val offenderTypes = MarsMusicPlayer::class.java.declaredFields
            .map { it.type.name }
            .filter { it == "android.media.MediaPlayer" }
        assertTrue("MediaPlayer field(s) survived the Media3 rewrite: $offenderTypes", offenderTypes.isEmpty())
    }

    @Test
    fun `no field type references MediaPlayer at all`() {
        // Stronger sweep: any declared field whose type name even mentions MediaPlayer
        // (the engine itself, a listener SAM over it, a holder class) is a regression —
        // the offload trap is bound to the engine type, not only to the exact class.
        val offenderTypes = MarsMusicPlayer::class.java.declaredFields
            .map { it.type.name }
            .filter { it.contains("MediaPlayer") }
        assertTrue("MediaPlayer-flavored field type(s): $offenderTypes", offenderTypes.isEmpty())
    }

    @Test
    fun `transport binds a media3 ExoPlayer engine field`() {
        // Positive half of the pin: the swap must actually land on Media3 (a transport with
        // NO engine field at all would pass the negative assertions above by deleting code).
        val engineFields = MarsMusicPlayer::class.java.declaredFields
            .map { it.type.name }
            .filter { it == "androidx.media3.exoplayer.ExoPlayer" }
        assertEquals("expected exactly one ExoPlayer engine field", 1, engineFields.size)
    }

    @Test
    fun `stream url contract survives the engine rewrite`() {
        // MusicRepository.getStreamUrl parity: {BASE_URL}/api/stream/{id} with the default
        // quality profile — the same address the TV client plays; the engine swap and the
        // quality parameter must not touch the query-free default.
        MarsMusicPlayer.setStreamQuality(StreamQuality.DEFAULT)
        assertEquals("https://marscore.ru/music/api/stream/t42", MarsMusicPlayer.streamUrlFor("t42"))
    }
}
