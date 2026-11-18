package ru.mars.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.repository.ServerTtsResult
import ru.mars.network.repository.ServerTtsVoices

/**
 * Политика озвучки: облако основное, локальная модель Piper — единственный резерв, системный
 * TTS — только видимый последний резерв.
 *
 * Проверяется ПРОВОДКА, которую строит [LocalVoiceTts], собранная здесь вручную из тех же
 * классов: `PreferOfflineEngine(offline = piper, fallback = ServerTtsSpeechEngine(fallback =
 * piper))`. Именно эта связка закрывает подтверждённый дефект «сетевой fallback уходит в
 * системный вместо единственной офлайн-модели». Сам [LocalVoiceTts] в JVM-тесте не создать
 * (Android `Context`/`Handler`), поэтому проверяется чистый движок с той же топологией.
 */
class PreferOfflineEngineTest {

    /** Движок-пустышка: пишет реплики и умеет отказывать. */
    private class FakeEngine(private var accept: Boolean = true) : VoiceOutputController.SpeechEngine {
        val spoken = mutableListOf<String>()
        var stopCount = 0
        var shutdownCount = 0

        override fun speak(text: String): Boolean {
            if (!accept) return false
            spoken += text
            return true
        }

        override fun stop() {
            stopCount++
        }

        override fun shutdown() {
            shutdownCount++
        }

        fun setAccept(value: Boolean) {
            accept = value
        }
    }

    /** Синтез, который всегда отказывает: сеть недоступна. */
    private class UnavailableClient(
        private val result: ServerTtsResult = ServerTtsResult.Unavailable("unauthorized", 401),
    ) : ServerTtsClient {
        val requests = mutableListOf<String>()

        override suspend fun synthesize(text: String): ServerTtsResult {
            requests += text
            return result
        }
    }

    /** Синтез, который отдаёт аудио. */
    private class AudioClient : ServerTtsClient {
        val requests = mutableListOf<String>()

        override suspend fun synthesize(text: String): ServerTtsResult {
            requests += text
            return ServerTtsResult.Audio(byteArrayOf(1, 2), "audio/mpeg")
        }
    }

    /** Плеер, который не смог начать: тот же исход, что «нет кодека/файла». */
    private class DeadPlayback : ServerTtsPlayback {
        override fun play(bytes: ByteArray, contentType: String, onFailed: () -> Unit): Boolean = false
        override fun stop() = Unit
        override fun release() = Unit
    }

    /** Плеер, который реально играет. */
    private class LivePlayback : ServerTtsPlayback {
        val played = mutableListOf<ByteArray>()

        override fun play(bytes: ByteArray, contentType: String, onFailed: () -> Unit): Boolean {
            played += bytes
            return true
        }

        override fun stop() = Unit
        override fun release() = Unit
    }

    /** Облачный движок с резервом [reserve] — та же сборка, что в [LocalVoiceTts]. */
    private fun cloud(
        reserve: VoiceOutputController.SpeechEngine,
        client: ServerTtsClient,
        playback: ServerTtsPlayback = DeadPlayback(),
        reserveChannel: VoiceChannel = VoiceChannel.OFFLINE,
        onSubstituted: (VoiceSubstitution) -> Unit = {},
    ) = ServerTtsSpeechEngine(
        fallback = reserve,
        client = client,
        playback = playback,
        scope = CoroutineScope(Dispatchers.Unconfined),
        fallbackChannel = reserveChannel,
        onVoiceSubstituted = onSubstituted,
    )

    // --- Облако отказало: говорит Piper, а не платформа --------------------------------

    @Test
    fun `cloud failure speaks through piper and never through the platform`() {
        val piper = FakeEngine()
        val platform = FakeEngine()
        val events = mutableListOf<VoiceSubstitution>()
        val prefer = PreferOfflineEngine(
            offline = piper,
            fallback = cloud(piper, UnavailableClient(), onSubstituted = { events += it }),
            onVoiceSubstituted = { events += it },
        )
        prefer.setProsody(VoiceProsody(voiceId = ServerTtsVoices.NATURAL_SVETLANA.id))

        assertTrue(prefer.speak("привет, Марс"))

        // Реплика прозвучала локальной моделью — единственным разрешённым резервом.
        assertEquals(listOf("привет, Марс"), piper.spoken)
        assertTrue("платформа не должна участвовать в цепочке вовсе", platform.spoken.isEmpty())
        // И об этом сообщено: подмена больше не тихая.
        assertEquals(
            listOf(VoiceSubstitution(VoiceChannel.OFFLINE, VoiceSubstitutionReason.CLOUD_FAILED)),
            events,
        )
    }

    @Test
    fun `cloud audio that cannot start is replaced by the local model`() {
        val piper = FakeEngine()
        val prefer = PreferOfflineEngine(
            offline = piper,
            fallback = cloud(piper, AudioClient(), playback = DeadPlayback()),
        )
        prefer.setProsody(VoiceProsody(voiceId = ServerTtsVoices.NATURAL_DMITRY.id))

        assertTrue(prefer.speak("фраза"))

        assertEquals(listOf("фраза"), piper.spoken)
    }

    @Test
    fun `working cloud keeps piper silent`() {
        val piper = FakeEngine()
        val playback = LivePlayback()
        val events = mutableListOf<VoiceSubstitution>()
        val prefer = PreferOfflineEngine(
            offline = piper,
            fallback = cloud(piper, AudioClient(), playback, onSubstituted = { events += it }),
            onVoiceSubstituted = { events += it },
        )
        prefer.setProsody(VoiceProsody(voiceId = ServerTtsVoices.NATURAL_SVETLANA.id))

        assertTrue(prefer.speak("привет"))

        assertEquals(1, playback.played.size)
        assertTrue("при живом облаке локальная модель звучать не должна", piper.spoken.isEmpty())
        assertTrue("подмены не было — событий быть не должно", events.isEmpty())
    }

    // --- Выбран локальный голос: Piper, и только потом сеть -----------------------------

    @Test
    fun `chosen local voice never opens the cloud`() {
        val piper = FakeEngine()
        val client = AudioClient()
        val prefer = PreferOfflineEngine(offline = piper, fallback = cloud(piper, client))
        prefer.setProsody(VoiceProsody(voiceId = ServerTtsVoices.LOCAL_IRINA.id))

        assertTrue(prefer.speak("привет"))

        assertEquals(listOf("привет"), piper.spoken)
        assertTrue("локальный выбор не должен ходить в сеть", client.requests.isEmpty())
    }

    @Test
    fun `dead local model is replaced by the cloud channel and announced`() {
        val piper = FakeEngine(accept = false)
        val playback = LivePlayback()
        val events = mutableListOf<VoiceSubstitution>()
        val prefer = PreferOfflineEngine(
            offline = piper,
            fallback = cloud(piper, AudioClient(), playback, onSubstituted = { events += it }),
            onVoiceSubstituted = { events += it },
        )
        prefer.setProsody(VoiceProsody(voiceId = ServerTtsVoices.LOCAL_IRINA.id))

        assertTrue(prefer.speak("привет"))

        assertEquals(1, playback.played.size)
        assertEquals(
            listOf(VoiceSubstitution(VoiceChannel.CLOUD, VoiceSubstitutionReason.OFFLINE_FAILED)),
            events,
        )
    }

    // --- Платформа: только когда модели нет, и всегда с объявлением ---------------------

    @Test
    fun `platform voice is announced when no local model is wired`() {
        val deadPiper = FakeEngine(accept = false)
        val platform = FakeEngine()
        val events = mutableListOf<VoiceSubstitution>()
        val prefer = PreferOfflineEngine(
            offline = deadPiper,
            fallback = platform,
            onVoiceSubstituted = { events += it },
        )
        prefer.setProsody(VoiceProsody(voiceId = ServerTtsVoices.NATURAL_SVETLANA.id))

        assertTrue(prefer.speak("привет"))

        // Робот звучит только там, где нет ни модели, ни сети, — и его появление объяснено.
        assertEquals(listOf("привет"), platform.spoken)
        assertEquals(
            listOf(VoiceSubstitution(VoiceChannel.SYSTEM, VoiceSubstitutionReason.SYSTEM_RESERVE)),
            events,
        )
    }

    @Test
    fun `all channels refusing is an explicit false, not a pretend speech`() {
        val prefer = PreferOfflineEngine(
            offline = FakeEngine(accept = false),
            fallback = FakeEngine(accept = false),
        )
        prefer.setProsody(VoiceProsody(voiceId = ServerTtsVoices.NATURAL_SVETLANA.id))

        assertFalse("хост должен показать «озвучка недоступна», а не делать вид", prefer.speak("текст"))
    }

    @Test
    fun `blank text is not sent anywhere`() {
        val piper = FakeEngine()
        val platform = FakeEngine()
        val prefer = PreferOfflineEngine(offline = piper, fallback = platform)

        assertFalse(prefer.speak("   "))

        assertTrue(piper.spoken.isEmpty())
        assertTrue(platform.spoken.isEmpty())
    }

    // --- Тишина, предсинтез и освобождение ---------------------------------------------

    @Test
    fun `stop and shutdown reach both channels`() {
        val piper = FakeEngine()
        val platform = FakeEngine()
        val prefer = PreferOfflineEngine(offline = piper, fallback = platform)

        prefer.stop()
        prefer.shutdown()

        // Остановить надо ВСЁ, что может звучать: переключение движков не должно оставить
        // старую реплику в фоне.
        assertEquals(1, piper.stopCount)
        assertEquals(1, platform.stopCount)
        assertEquals(1, piper.shutdownCount)
        assertEquals(1, platform.shutdownCount)
    }

    @Test
    fun `local voice does not warm the network`() {
        val network = object : VoiceOutputController.SpeechEngine {
            val prefetched = mutableListOf<String>()
            override fun speak(text: String): Boolean = true
            override fun stop() = Unit
            override fun shutdown() = Unit
            override fun prefetch(text: String) {
                prefetched += text
            }
        }
        // Обёртка вокруг готового движка: проверяется только маршрутизация предсинтеза.
        val prefer = PreferOfflineEngine(offline = FakeEngine(), fallback = network)

        prefer.setProsody(VoiceProsody(voiceId = ServerTtsVoices.LOCAL_IRINA.id))
        prefer.prefetch("кусок")

        assertTrue("локальный голос синтезирует на месте, греть сеть незачем", network.prefetched.isEmpty())
    }

    @Test
    fun `network voice prefetches the cloud channel`() {
        val client = UnavailableClient()
        val playback = LivePlayback()
        val piper = FakeEngine()
        val prefer = PreferOfflineEngine(
            offline = piper,
            fallback = cloud(piper, client, playback),
        )
        prefer.setProsody(VoiceProsody(voiceId = ServerTtsVoices.NATURAL_SVETLANA.id))

        prefer.prefetch("следующий кусок")

        // Предсинтез серверного канала: запрос уходит заранее, под звук предыдущего куска.
        assertEquals(listOf("следующий кусок"), client.requests)
    }
}
