package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression pin for the Mi TV music-silence fix (ru.mars.tv, Android 9 MSSP1): the
 * global transport must hold NO android.media.MediaPlayer. Live diagnostics showed the
 * MediaPlayer AAC stream sinking into AudioFlinger's compressed-offload branch
 * («Offload request, forcing to Direct Output», flags #10 COMPRESS_OFFLOAD, frozen
 * «Frames written») — silent on Speaker/Bluetooth while mixer sounds stayed audible.
 * The replacement is Media3 ExoPlayer 1.2.1, which has no offload route at all: it
 * software-decodes to PCM and renders through a regular AudioTrack (the audible mixer
 * path). This test guards the field surface reflectively (pure JVM — no Android runtime,
 * no class initialization: `::class.java` does not trigger the object's <clinit>) so a
 * future refactor cannot silently re-introduce the offload-prone engine.
 */
class TvMusicPlayerNoMediaPlayerTest {

    @Test
    fun `transport declares no android MediaPlayer field`() {
        val offenderTypes = TvMusicPlayer::class.java.declaredFields
            .map { it.type.name }
            .filter { it == "android.media.MediaPlayer" }
        assertTrue("MediaPlayer field(s) survived the Media3 rewrite: $offenderTypes", offenderTypes.isEmpty())
    }

    @Test
    fun `no field type references MediaPlayer at all`() {
        // Stronger sweep: any declared field whose type name even mentions MediaPlayer
        // (the engine itself, a listener SAM over it, a holder class) is a regression —
        // the offload trap is bound to the engine type, not only to the exact class.
        val offenderTypes = TvMusicPlayer::class.java.declaredFields
            .map { it.type.name }
            .filter { it.contains("MediaPlayer") }
        assertTrue("MediaPlayer-flavored field type(s): $offenderTypes", offenderTypes.isEmpty())
    }

    @Test
    fun `stream url contract survives the engine rewrite`() {
        // MusicRepository.getStreamUrl parity: {MUSIC_BASE_URL}/api/stream/{id} — the
        // same address the mobile client plays; the engine swap must not touch it.
        assertEquals("https://marscore.ru/music/api/stream/t42", TvMusicPlayer.streamUrlFor("t42"))
    }
}
