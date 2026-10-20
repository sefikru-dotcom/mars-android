package ru.mars.mobile

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-тесты политики захвата ([AudioCapturePolicy], [AudioCaptureSelector], [AudioCaptureEffects])
 * без Android и без Robolectric.
 *
 * Проверяют именно то, что нельзя проверить на устройстве в CI:
 * - порядок кандидатов источника и смысл флага `echoCancellation`;
 * - откат на MIC, когда предпочтительный источник не инициализировался (фейк-фабрика роняет первую
 *   попытку) — и ОТСУТСТВИЕ отката при отказе в разрешении;
 * - отсутствие аппаратного эффекта (isAvailable false, create null, setEnabled ошибка, исключение)
 *   не роняет захват;
 * - освобождение эффектов ровно один раз вместе с AudioRecord.
 *
 * Реальный AudioRecord/AcousticEchoCanceler здесь не создаётся: платформенный адаптер
 * ([AndroidAudioSourceFactory]) проверяется пином исходника ниже.
 */
class AudioCapturePolicyTest {

    // --- fakes ---------------------------------------------------------------------------------

    private class FakeSource : AudioSource {
        val releases = AtomicInteger(0)

        override fun read(buf: ByteArray): Int = -1

        override fun release() {
            releases.incrementAndGet()
        }
    }

    /** Фабрика, которая роняет выбранные кандидаты и считает порядок попыток. */
    private class FakeOpener(
        private val failures: Map<CaptureSource, () -> Exception> = emptyMap(),
    ) {
        val attempts = CopyOnWriteArrayList<CaptureSource>()
        val sources = CopyOnWriteArrayList<FakeSource>()

        fun open(source: CaptureSource): AudioSource {
            attempts.add(source)
            failures[source]?.let { throw it() }
            return FakeSource().also { sources.add(it) }
        }
    }

    private class FakeEffect(override val name: String, private val throwOnRelease: Boolean = false) : CaptureEffect {
        val releases = AtomicInteger(0)

        override fun release() {
            releases.incrementAndGet()
            if (throwOnRelease) throw IllegalStateException("effect already dead")
        }
    }

    /** Отказы по кандидатам: каждый роняет свою попытку заданным исключением. */
    private fun failing(vararg pairs: Pair<CaptureSource, Exception>): Map<CaptureSource, () -> Exception> =
        pairs.associate { (source, error) -> source to { error } }

    private fun selector(
        opener: FakeOpener,
        failed: MutableList<CaptureSource> = CopyOnWriteArrayList(),
    ): AudioCaptureSelector = AudioCaptureSelector(
        openSource = opener::open,
        onAttemptFailed = { source, _ -> failed.add(source) },
    )

    // --- source order ---------------------------------------------------------------------------

    @Test
    fun `echo cancellation tries voice communication before mic`() {
        assertEquals(
            listOf(CaptureSource.VOICE_COMMUNICATION, CaptureSource.MIC),
            AudioCapturePolicy.candidates(echoCancellation = true),
        )
    }

    @Test
    fun `echo cancellation off keeps the legacy mic only capture`() {
        assertEquals(
            listOf(CaptureSource.MIC),
            AudioCapturePolicy.candidates(echoCancellation = false),
        )
    }

    @Test
    fun `echo cancellation is on by default so barge-in gets the platform canceller`() {
        assertTrue(AudioCapturePolicy.DEFAULT_ECHO_CANCELLATION)
        assertTrue(AudioCapturePolicy.DEFAULT_NOISE_SUPPRESSION)
        assertEquals(CaptureSource.VOICE_COMMUNICATION, AudioCapturePolicy.candidates(true).first())
    }

    // --- fallback -------------------------------------------------------------------------------

    @Test
    fun `opens the preferred source when it initializes`() {
        val opener = FakeOpener()
        val opened = selector(opener).open(echoCancellation = true)

        assertEquals(CaptureSource.VOICE_COMMUNICATION, opened.captureSource)
        assertFalse(opened.fallbackUsed)
        assertEquals(listOf(CaptureSource.VOICE_COMMUNICATION), opener.attempts)
        assertTrue(opened.source === opener.sources.single())
    }

    @Test
    fun `falls back to mic when voice communication fails to initialize`() {
        val opener = FakeOpener(
            failures = failing(
                CaptureSource.VOICE_COMMUNICATION to IllegalStateException("not initialized"),
            ),
        )
        val failed = CopyOnWriteArrayList<CaptureSource>()

        val opened = selector(opener, failed).open(echoCancellation = true)

        assertEquals(listOf(CaptureSource.VOICE_COMMUNICATION, CaptureSource.MIC), opener.attempts)
        assertEquals(CaptureSource.MIC, opened.captureSource)
        assertTrue(opened.fallbackUsed)
        assertEquals(listOf(CaptureSource.VOICE_COMMUNICATION), failed)
        // Провалившаяся попытка не отдаёт источник: открыт ровно один, и это MIC.
        assertEquals(1, opener.sources.size)
    }

    @Test
    fun `falls back when start recording is refused by the platform`() {
        val opener = FakeOpener(
            failures = failing(
                CaptureSource.VOICE_COMMUNICATION to RuntimeException("startRecording failed"),
            ),
        )

        val opened = selector(opener).open(echoCancellation = true)

        assertEquals(CaptureSource.MIC, opened.captureSource)
        assertTrue(opened.fallbackUsed)
    }

    @Test
    fun `a denied microphone is never retried on the fallback source`() {
        val opener = FakeOpener(
            failures = failing(
                CaptureSource.VOICE_COMMUNICATION to
                    MicrophonePermissionDeniedException("RECORD_AUDIO is not granted"),
            ),
        )

        val error = assertThrows(MicrophonePermissionDeniedException::class.java) {
            selector(opener).open(echoCancellation = true)
        }

        assertEquals("RECORD_AUDIO is not granted", error.message)
        assertEquals(listOf(CaptureSource.VOICE_COMMUNICATION), opener.attempts)
    }

    @Test
    fun `echo cancellation off never touches voice communication`() {
        val opener = FakeOpener()

        val opened = selector(opener).open(echoCancellation = false)

        assertEquals(listOf(CaptureSource.MIC), opener.attempts)
        assertEquals(CaptureSource.MIC, opened.captureSource)
        assertFalse(opened.fallbackUsed)
    }

    @Test
    fun `when every candidate fails the last failure surfaces with the earlier one suppressed`() {
        val opener = FakeOpener(
            failures = failing(
                CaptureSource.VOICE_COMMUNICATION to IllegalStateException("vc not initialized"),
                CaptureSource.MIC to IllegalArgumentException("mic not initialized"),
            ),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            selector(opener).open(echoCancellation = true)
        }

        assertEquals("mic not initialized", error.message)
        assertEquals(
            listOf("vc not initialized"),
            error.suppressed.map { it.message },
        )
    }

    @Test
    fun `fallback is worth trying for every failure except a permission denial`() {
        assertTrue(AudioCapturePolicy.isFallbackWorthy(IllegalStateException("uninitialized")))
        assertTrue(AudioCapturePolicy.isFallbackWorthy(RuntimeException("start failed")))
        assertFalse(AudioCapturePolicy.isFallbackWorthy(SecurityException("denied")))
        assertFalse(
            AudioCapturePolicy.isFallbackWorthy(
                MicrophonePermissionDeniedException("denied"),
            ),
        )
    }

    // --- hardware effects -----------------------------------------------------------------------

    @Test
    fun `effect plan follows the two flags`() {
        assertEquals(
            listOf(CaptureEffectKind.ACOUSTIC_ECHO_CANCELER, CaptureEffectKind.NOISE_SUPPRESSOR),
            AudioCapturePolicy.effectPlan(echoCancellation = true, noiseSuppression = true),
        )
        assertEquals(
            listOf(CaptureEffectKind.ACOUSTIC_ECHO_CANCELER),
            AudioCapturePolicy.effectPlan(echoCancellation = true, noiseSuppression = false),
        )
        assertEquals(
            listOf(CaptureEffectKind.NOISE_SUPPRESSOR),
            AudioCapturePolicy.effectPlan(echoCancellation = false, noiseSuppression = true),
        )
        assertTrue(
            AudioCapturePolicy.effectPlan(echoCancellation = false, noiseSuppression = false).isEmpty(),
        )
    }

    @Test
    fun `a missing effect is reported but does not break capture`() {
        val activation = AudioCapturePolicy.activateEffects(
            AudioCapturePolicy.effectPlan(echoCancellation = true, noiseSuppression = true),
        ) { kind ->
            when (kind) {
                CaptureEffectKind.ACOUSTIC_ECHO_CANCELER -> EffectAcquisition.Unavailable
                CaptureEffectKind.NOISE_SUPPRESSOR -> EffectAcquisition.Enabled(FakeEffect("ns"))
            }
        }

        assertEquals(EffectDecision.UNAVAILABLE, activation.decision(CaptureEffectKind.ACOUSTIC_ECHO_CANCELER))
        assertEquals(EffectDecision.ENABLED, activation.decision(CaptureEffectKind.NOISE_SUPPRESSOR))
        assertEquals(listOf("ns"), activation.effects.names)
        assertFalse(activation.effects.isEmpty)
    }

    @Test
    fun `a throwing effect acquisition is a failed decision, not an exception`() {
        val activation = AudioCapturePolicy.activateEffects(
            AudioCapturePolicy.effectPlan(echoCancellation = true, noiseSuppression = true),
        ) { kind ->
            if (kind == CaptureEffectKind.ACOUSTIC_ECHO_CANCELER) {
                throw UnsupportedOperationException("audiofx unavailable on this device")
            }
            EffectAcquisition.Enabled(FakeEffect("ns"))
        }

        assertEquals(EffectDecision.FAILED, activation.decision(CaptureEffectKind.ACOUSTIC_ECHO_CANCELER))
        assertEquals(EffectDecision.ENABLED, activation.decision(CaptureEffectKind.NOISE_SUPPRESSOR))
        assertEquals(listOf("ns"), activation.effects.names)
    }

    @Test
    fun `a refused setEnabled is a failed decision and the effect is not held`() {
        val activation = AudioCapturePolicy.activateEffects(
            AudioCapturePolicy.effectPlan(echoCancellation = true, noiseSuppression = false),
        ) { EffectAcquisition.Failed }

        assertEquals(EffectDecision.FAILED, activation.decision(CaptureEffectKind.ACOUSTIC_ECHO_CANCELER))
        assertTrue(activation.effects.isEmpty)
        // Эффект, которого не запрашивали, вообще не попадает в план и не считается отказом.
        assertEquals(EffectDecision.NOT_REQUESTED, activation.decision(CaptureEffectKind.NOISE_SUPPRESSOR))
    }

    @Test
    fun `no flags requested means no effects at all`() {
        val activation = AudioCapturePolicy.activateEffects(emptyList()) {
            throw AssertionError("no effect must be acquired when the plan is empty")
        }

        assertTrue(activation.effects.isEmpty)
        assertEquals(EffectDecision.NOT_REQUESTED, activation.decision(CaptureEffectKind.ACOUSTIC_ECHO_CANCELER))
    }

    @Test
    fun `effects are released exactly once across repeated releases`() {
        val aec = FakeEffect("aec")
        val ns = FakeEffect("ns")
        val effects = AudioCaptureEffects(listOf(aec, ns))

        effects.release()
        effects.release()
        effects.release()

        assertTrue(effects.isReleased)
        assertEquals(1, aec.releases.get())
        assertEquals(1, ns.releases.get())
        assertEquals(listOf("aec", "ns"), effects.names)
    }

    @Test
    fun `a failing effect does not keep the others from being released`() {
        val broken = FakeEffect("aec", throwOnRelease = true)
        val healthy = FakeEffect("ns")
        val effects = AudioCaptureEffects(listOf(broken, healthy))

        effects.release()

        assertEquals(1, broken.releases.get())
        assertEquals(1, healthy.releases.get())
        assertTrue(effects.isReleased)
    }

    @Test
    fun `empty effects release is a no-op`() {
        val effects = AudioCaptureEffects()

        effects.release()
        effects.release()

        assertTrue(effects.isEmpty)
        assertTrue(effects.isReleased)
    }

    // --- diagnostics ----------------------------------------------------------------------------

    @Test
    fun `diagnostics line reports the source, the fallback and each effect decision`() {
        val activation = AudioCapturePolicy.activateEffects(
            AudioCapturePolicy.effectPlan(echoCancellation = true, noiseSuppression = true),
        ) { kind ->
            when (kind) {
                CaptureEffectKind.ACOUSTIC_ECHO_CANCELER -> EffectAcquisition.Enabled(FakeEffect("aec"))
                CaptureEffectKind.NOISE_SUPPRESSOR -> EffectAcquisition.Unavailable
            }
        }
        val diagnostics = AudioCaptureDiagnostics(
            captureSource = CaptureSource.MIC,
            fallbackUsed = true,
            echoCancellationRequested = true,
            noiseSuppressionRequested = true,
            activation = activation,
        )

        assertEquals(
            "capture source=mic fallback=true echo_cancellation=on noise_suppression=on " +
                "aec=ENABLED ns=UNAVAILABLE",
            diagnostics.logLine(),
        )
        assertTrue(diagnostics.echoCanceled)
        assertFalse(diagnostics.noiseSuppressed)
    }

    @Test
    fun `diagnostics says echo cancellation is off when the flag is off`() {
        val diagnostics = AudioCaptureDiagnostics(
            captureSource = CaptureSource.MIC,
            fallbackUsed = false,
            echoCancellationRequested = false,
            noiseSuppressionRequested = false,
        )

        assertEquals(
            "capture source=mic fallback=false echo_cancellation=off noise_suppression=off " +
                "aec=NOT_REQUESTED ns=NOT_REQUESTED",
            diagnostics.logLine(),
        )
        assertFalse(diagnostics.echoCanceled)
    }

    // --- production wiring pins -----------------------------------------------------------------

    /**
     * Пин исходника: правила выше живут в чистой политике, но их должно быть чем исполнить на
     * платформе. Если кто-то вернёт жёсткий `MIC` или уберёт аппаратный AEC, тест упадёт здесь, а не
     * на устройстве при включённом перебивании.
     */
    @Test
    fun `production factory opens voice communication first and installs hardware aec`() {
        val factory = source("src/main/java/ru/mars/mobile/AndroidAudioSourceFactory.kt")

        assertTrue(
            "factory must map the preferred candidate to VOICE_COMMUNICATION",
            factory.contains("MediaRecorder.AudioSource.VOICE_COMMUNICATION"),
        )
        assertTrue(
            "factory must keep the explicit MIC fallback",
            factory.contains("MediaRecorder.AudioSource.MIC"),
        )
        assertTrue("factory must go through the pure policy", factory.contains("AudioCaptureSelector"))
        assertTrue(
            "factory must request the hardware echo canceller",
            factory.contains("AcousticEchoCanceler"),
        )
        assertTrue(
            "factory must request the hardware noise suppressor",
            factory.contains("NoiseSuppressor"),
        )
        assertTrue(
            "effects must be released together with the record",
            factory.contains("effects.release()"),
        )
        assertTrue(
            "factory must report the capture decision for on-device measurements",
            factory.contains("AudioCaptureDiagnostics"),
        )
    }

    /** Пин исходника: озвучка ассистента не должна уходить в медиа-профиль. */
    @Test
    fun `assistant speech output is routed as USAGE_ASSISTANT, not media`() {
        val serverPlayback = source("src/main/java/ru/mars/mobile/AndroidServerTtsPlayback.kt")
        assertTrue(
            "server TTS playback must use USAGE_ASSISTANT",
            serverPlayback.contains("C.USAGE_ASSISTANT"),
        )
        assertFalse(
            "server TTS playback must not fall back to the media route",
            serverPlayback.contains("C.USAGE_MEDIA"),
        )

        val platformTts = source("src/main/java/ru/mars/mobile/AndroidSpeechEngine.kt")
        assertTrue(
            "platform TTS must declare assistant speech attributes",
            platformTts.contains("AudioAttributes.USAGE_ASSISTANT"),
        )
        assertTrue(
            "platform TTS must declare the speech content type",
            platformTts.contains("AudioAttributes.CONTENT_TYPE_SPEECH"),
        )
    }

    /** Unit-тесты Gradle работают из каталога модуля; подъём вверх — для worktree/CI с другим cwd. */
    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            val nested = File(dir, "app-mobile/$relative")
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile
        }
        throw AssertionError("source not found: $relative")
    }
}
