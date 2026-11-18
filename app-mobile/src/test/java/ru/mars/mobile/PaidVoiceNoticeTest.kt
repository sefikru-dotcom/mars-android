package ru.mars.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.repository.EdgeNeuralTtsClient
import ru.mars.network.repository.PaidTtsClient
import ru.mars.network.repository.ServerTtsResult
import ru.mars.network.repository.ServerTtsVoices

/**
 * Платный голос PlusVibe не прозвучал — человек обязан узнать, чем прочитана фраза.
 *
 * ## Почему это отдельный тест
 * В ветке платного голоса отказ клиента не доходит до движка: `RepositoryServerTtsClient`
 * сам уходит на нейро-резерв и возвращает уже готовое аудио. То есть до появления
 * `onPaidUnavailable` человек, выбравший «Алису» без настроенного профиля PlusVibe, слышал
 * Дмитрия и не получал ни одного объяснения — ровно тот класс дефекта, который в этом
 * приложении объявлен запрещённым («резерв разрешён, молчаливая подмена — нет»).
 */
class PaidVoiceNoticeTest {

    @Test
    fun `paid voice failure is announced with its reason when the neural reserve speaks`() {
        val reasons = mutableListOf<String?>()
        val client = client(
            paid = FakePaid(ServerTtsResult.Unavailable(PlusVibeTtsProfile.REASON_NO_PROFILE)),
            onPaidUnavailable = { reason -> reasons += reason },
        )

        val result = runBlocking { client.synthesize("фраза") }

        assertTrue("фразу обязан прочитать резерв, а не потерять", result is ServerTtsResult.Audio)
        assertEquals(
            "причина отказа обязана дойти до хоста без искажений",
            listOf<String?>(PlusVibeTtsProfile.REASON_NO_PROFILE),
            reasons,
        )
    }

    @Test
    fun `paid voice success is not announced as a substitution`() {
        val reasons = mutableListOf<String?>()
        val client = client(
            paid = FakePaid(ServerTtsResult.Audio(byteArrayOf(7), "audio/mpeg")),
            onPaidUnavailable = { reason -> reasons += reason },
        )

        runBlocking { client.synthesize("фраза") }

        assertTrue("подмены не было — событие не должно ложно срабатывать", reasons.isEmpty())
    }

    @Test
    fun `notice text names the missing plusvibe profile instead of blaming the network`() {
        assertEquals(
            VoiceSubstitutionNotice.PLUSVIBE_NOT_CONFIGURED,
            VoiceSubstitutionNotice.paidUnavailable(PlusVibeTtsProfile.REASON_NO_PROFILE),
        )
        // Остальные отказы (нет ключа, таймаут, транспорт) — про провайдера и сеть, не про
        // настройки: им достаётся общий текст.
        assertEquals(
            VoiceSubstitutionNotice.PAID_UNAVAILABLE,
            VoiceSubstitutionNotice.paidUnavailable(PlusVibeTtsProfile.REASON_NO_KEY),
        )
        assertEquals(
            VoiceSubstitutionNotice.PAID_UNAVAILABLE,
            VoiceSubstitutionNotice.paidUnavailable("timeout"),
        )
        assertEquals(
            VoiceSubstitutionNotice.PAID_UNAVAILABLE,
            VoiceSubstitutionNotice.paidUnavailable(null),
        )
    }

    // --- вспомогательное --------------------------------------------------------------------

    /** Клиент с выбранным платным голосом и подменёнными транспортами. */
    private fun client(
        paid: PaidTtsClient,
        onPaidUnavailable: (String?) -> Unit,
    ): RepositoryServerTtsClient = RepositoryServerTtsClient(
        baseUrl = "https://marscore.ru",
        credentialProvider = { null },
        prosody = VoiceProsody(voiceId = ServerTtsVoices.PLUSVIBE_ALICE.id),
        directEdgeClient = FakeEdge(),
        paidClient = paid,
        transport = ServerTtsTransport { _, _, _ ->
            ServerTtsResult.Unavailable("server_error", 503)
        },
        onPaidUnavailable = onPaidUnavailable,
    )

    /** Платный клиент с заданным исходом. */
    private class FakePaid(private val result: ServerTtsResult) : PaidTtsClient {
        override suspend fun synthesize(
            text: String,
            voice: String,
            model: String,
            speed: Float,
            instructions: String?,
            voiceId: String?,
        ): ServerTtsResult = result
    }

    /** Нейро-резерв (Дмитрий): всегда даёт звук, чтобы подмена была видна. */
    private class FakeEdge : EdgeNeuralTtsClient() {
        override suspend fun synthesize(
            text: String,
            voice: String,
            rate: String?,
            pitch: String?,
        ): ServerTtsResult = ServerTtsResult.Audio(byteArrayOf(1), "audio/mpeg")
    }
}
