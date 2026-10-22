package ru.mars.mobile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.repository.ServerTtsResult

/**
 * Регрессия по блокеру «ход глохнет после первой паузы в аудио».
 *
 * Что было сломано (HEAD c666a04): опустевшая очередь фраз (`onDrained`) считалась концом хода.
 * Хост выключал флаг озвучки навсегда, поэтому ход, у которого между фразами была пауза
 * (типичный случай — «Проверяю…» → инструмент → ответ), терял весь дальнейший текст: поздние
 * дельты не звучали, а финальный `Done` не читался, потому что «ответ уже пошёл в звук».
 *
 * Тесты поведенческие: проверяются РЕШЕНИЯ жизненного цикла и РЕАЛЬНЫЙ звук очереди фраз
 * (синтез + плеер), а не наличие строк в исходнике.
 */
class StreamSpeechLifecycleTest {

    // --- 1. решения жизненного цикла -----------------------------------------------------------

    @Test
    fun `пустая очередь между фразами не выключает озвучку хода`() {
        val turn = StreamSpeechLifecycle()
        turn.begin(enabled = true)

        // «Проверяю…» прозвучало, очередь опустела — но ход ещё идёт.
        assertFalse(
            "пауза внутри хода не должна объявлять конец ответа",
            turn.onQueueDrained(),
        )
        assertTrue("поздние дельты этого хода всё ещё озвучиваются", turn.acceptsDelta)
    }

    @Test
    fun `паузы обычного ответа не глушат его вторую половину`() {
        val turn = StreamSpeechLifecycle()
        turn.begin(enabled = true)

        // Первая фраза доиграла, вторая ещё не пришла (модель думает).
        turn.onQueueDrained()
        assertTrue(turn.acceptsDelta)
        // Вторая фраза доиграла — снова пауза, и снова ход жив.
        turn.onQueueDrained()
        assertTrue(turn.acceptsDelta)
    }

    @Test
    fun `конец хода и опустевшая очередь дают ровно одно «ответ закончился»`() {
        val turn = StreamSpeechLifecycle()
        turn.begin(enabled = true)
        turn.end()

        assertTrue("после конца хода цикл должен услышать «доиграло»", turn.onQueueDrained())
        assertFalse("повторный drained не переводит цикл слушать второй раз", turn.onQueueDrained())
        assertFalse(turn.onQueueDrained())
    }

    @Test
    fun `после конца хода новые дельты не озвучиваются`() {
        val turn = StreamSpeechLifecycle()
        turn.begin(enabled = true)
        turn.end()

        assertFalse("поздняя дельта после Done не должна попасть в звук", turn.acceptsDelta)
    }

    @Test
    fun `отмена не взводит очередь обратно`() {
        val turn = StreamSpeechLifecycle()
        turn.begin(enabled = true)
        turn.cancel()

        assertFalse("после стоп/перебивания ход не озвучивается", turn.acceptsDelta)
        assertFalse("отменённый ход не объявляет «ответ закончился»", turn.onQueueDrained())
        assertFalse(turn.acceptsDelta)
    }

    @Test
    fun `новый ход сбрасывает состояние прошлого`() {
        val turn = StreamSpeechLifecycle()
        turn.begin(enabled = true)
        turn.end()
        turn.onQueueDrained()
        turn.cancel()

        turn.begin(enabled = true)
        assertTrue(turn.acceptsDelta)
        turn.end()
        assertTrue("в новом ходе «доиграло» снова сообщается", turn.onQueueDrained())
    }

    @Test
    fun `выключенная озвучка молчит и не трогает цикл`() {
        val turn = StreamSpeechLifecycle()
        turn.begin(enabled = false)

        assertFalse(turn.isSpeechEnabled)
        assertFalse(turn.acceptsDelta)
        assertFalse(turn.onQueueDrained())
    }

    // --- 2. сквозной сценарий хода: очередь фраз + политика + жизненный цикл --------------------

    /**
     * Сценарий из ревью: инструмент → «Проверяю…» → очередь опустела → поздняя дельта → Done.
     * Проводка повторяет порядок вызовов [ChatActivity] один в один, звук — настоящий: тот же
     * чанкер, нормализатор и очередь, что в приложении, только синтез и плеер фейковые.
     */
    @Test
    fun `ход с паузой на инструменте договаривает ответ после пустой очереди`() {
        val host = TurnHost()

        host.beginTurn()
        host.toolStarted("search")
        assertEquals("«Проверяю…» звучит сразу", 1, host.played.size)

        host.playback.finishCurrent() // «Проверяю…» доиграло — очередь пуста, ход продолжается
        assertEquals("пауза внутри хода — не конец ответа", 0, host.answerFinished)

        host.delta("Готово: двадцать градусов.")
        assertEquals("поздняя дельта обязана звучать", 2, host.played.size)
        assertTrue(host.played[1].contains("Готово"))

        host.playback.finishCurrent()
        host.done()

        assertEquals("ответ закончился ровно один раз", 1, host.answerFinished)
        assertNull("терминальный Done не дублирует озвученный ответ", host.finalText)
    }

    @Test
    fun `пауза посреди обычного ответа не съедает следующее предложение`() {
        val host = TurnHost()
        host.beginTurn()

        // Обе фразы длиннее minPhraseLength (25), иначе чанкер держит короткую фразу до flush
        // и пауза проверяла бы не то поведение.
        host.delta("Первое предложение про погоду.")
        host.playback.finishCurrent()
        host.delta("Второе предложение про ветер.")
        host.playback.finishCurrent()
        host.done()

        assertEquals(2, host.played.size)
        assertTrue(host.played[1].contains("Второе"))
        assertEquals(1, host.answerFinished)
    }

    @Test
    fun `конец хода доигрывает очередь и сообщает о конце один раз`() {
        val host = TurnHost()
        host.beginTurn()

        host.delta("Ответ без точки")
        host.done() // хвост уходит в очередь здесь
        host.playback.finishCurrent()
        host.playback.finishCurrent()

        assertEquals(1, host.played.size)
        assertEquals(1, host.answerFinished)
    }

    @Test
    fun `стоп во время хода не озвучивает поздний текст`() {
        val host = TurnHost()
        host.beginTurn()
        host.delta("Первое предложение.")
        assertEquals(1, host.played.size)

        host.cancel() // перебивание/стоп: тишина немедленно
        host.delta("Второе предложение.")
        host.playback.finishCurrent()

        assertEquals("после стоп хвост не догоняет", 1, host.played.size)
        assertEquals("отменённый ход не объявляет конец ответа", 0, host.answerFinished)
    }

    @Test
    fun `ход без включённой озвучки не звучит и не трогает цикл`() {
        val host = TurnHost()
        host.beginTurn(enabled = false)

        host.delta("Ответ.")
        host.toolStarted("search")
        host.done()
        host.playback.finishCurrent()

        assertTrue(host.played.isEmpty())
        assertEquals(0, host.answerFinished)
    }

    // --- 3. стенд: та же проводка, что в ChatActivity -------------------------------------------

    /**
     * Мини-хост: повторяет решения [ChatActivity] (`beginStreamSpeech` / `onAssistantDelta` /
     * `announceToolProgress` / `endStreamSpeech` / `cancelStreamSpeech` / `onStreamSpeechDrained`),
     * но без Android. Смысл — проверить СВЯЗКУ «политика речи + жизненный цикл + очередь»,
     * потому что порознь каждая часть выглядит правильной.
     */
    private class TurnHost {

        val policy = VoiceTurnSpeechPolicy()
        val lifecycle = StreamSpeechLifecycle()
        val client = RecordingClient()
        val playback = RecordingPlayback()
        val controller = StreamingSpeechController(
            queue = SpeechQueue(
                client = client,
                playback = playback,
                scope = CoroutineScope(Dispatchers.Unconfined),
                onDrained = { drained() },
                retryPolicy = SpeechRetryPolicy(maxAttempts = 1, maxDelayMs = 0L),
            ),
        )

        /** Сколько раз цикл услышал «ответ закончился». */
        var answerFinished: Int = 0
            private set

        val played: List<String> get() = playback.played

        /** Результат терминального `Done` (текст, который надо прочитать, или null). */
        var finalText: String? = null
            private set

        fun beginTurn(enabled: Boolean = true) {
            lifecycle.begin(enabled)
            controller.stop()
            policy.onTurnStarted()
        }

        fun delta(text: String) {
            val speakable = policy.onGeneratedText(text) ?: return
            if (!lifecycle.acceptsDelta) return
            controller.onDelta(speakable)
        }

        fun toolStarted(name: String) {
            if (!lifecycle.isSpeechEnabled) return
            val line = policy.onToolStarted(name) ?: return
            controller.speakWhole(line)
        }

        fun done() {
            if (!lifecycle.isSpeechEnabled) return
            finalText = policy.onFinal("Готово: двадцать градусов.")
            controller.onTurnFinished()
            lifecycle.end()
            if (!controller.hasPendingSpeech) drained()
        }

        fun cancel() {
            lifecycle.cancel()
            controller.stop()
        }

        private fun drained() {
            if (!lifecycle.isSpeechEnabled) return
            if (!lifecycle.onQueueDrained()) return
            answerFinished++
        }
    }

    private class RecordingClient : ServerTtsClient {
        override suspend fun synthesize(text: String): ServerTtsResult =
            ServerTtsResult.Audio(text.toByteArray(), "audio/wav")
    }

    private class RecordingPlayback : ServerTtsPlayback {

        val played: MutableList<String> = ArrayList()
        private var onFinished: (() -> Unit)? = null
        private var onFailed: (() -> Unit)? = null

        override fun play(bytes: ByteArray, contentType: String, onFailed: () -> Unit): Boolean = false

        override fun playToEnd(
            bytes: ByteArray,
            contentType: String,
            onFinished: () -> Unit,
            onFailed: () -> Unit,
        ): Boolean {
            played += bytes.toString(Charsets.UTF_8)
            this.onFinished = onFinished
            this.onFailed = onFailed
            return true
        }

        fun finishCurrent() {
            val callback = onFinished
            onFinished = null
            callback?.invoke()
        }

        override fun stop() {
            onFinished = null
        }

        override fun release() {
            onFinished = null
        }
    }

    // --- 4. конец хода, когда хвост ещё СИНТЕЗИРУЕТСЯ (последний блокер) -------------------------
    //
    // Блокер: `endStreamSpeech` спрашивал `isSpeaking` (флаг «играет прямо сейчас»). Хвостовая
    // фраза, ушедшая в синтез на `Done`, ещё не играет — предикат отвечал «звука нет», хост
    // объявлял конец хода, гасил streamSpeech, и следующая `onStreamSpeechPhraseStarted`
    // выключала уже готовое аудио. Различие `isSpeaking` / `hasPendingSpeech` проверяется здесь
    // на настоящей очереди: фраза висит в SYNTHESIZING, пока тест её не отпустит.

    @Test
    fun `хвост, ушедший в синтез на Done, не объявляет конец раньше звука`() {
        val host = DelayedTurnHost()
        host.beginTurn()
        // Короче minPhraseLength: чанкер держит фразу до flush на конце хода — единственная
        // фраза хода появляется именно на Done.
        host.delta("Ответ без точки")
        host.done()

        assertTrue("хвост ещё синтезируется — очередь не пуста", host.controller.hasPendingSpeech)
        assertFalse(
            "звука ещё нет: ровно здесь isSpeaking давал ложный drained",
            host.controller.isSpeaking,
        )
        assertEquals("цикл не должен услышать конец ответа до звука", 0, host.answerFinished)

        host.releaseSynthesis()
        assertEquals("хвост обязан зазвучать", 1, host.played.size)
        assertTrue(host.played.single().contains("Ответ"))

        host.playback.finishCurrent()
        assertEquals("конец ответа — один раз и после реального звука", 1, host.answerFinished)
    }

    @Test
    fun `стоп во время синтеза хвоста не оживляет очередь`() {
        val host = DelayedTurnHost()
        host.beginTurn()
        host.delta("Ответ без точки")
        host.done()
        assertTrue(host.controller.hasPendingSpeech)

        host.cancel() // перебивание/стоп посреди синтеза
        assertFalse("стоп чистит очередь", host.controller.hasPendingSpeech)
        assertEquals("отменённый ход не объявляет конец", 0, host.answerFinished)

        host.releaseSynthesis() // поздний ответ сервера
        assertTrue("поздний синтез после стопа не звучит", host.played.isEmpty())
        assertEquals(0, host.answerFinished)
    }

    @Test
    fun `пустая очередь на Done сообщает конец сразу`() {
        val host = DelayedTurnHost()
        host.beginTurn()
        host.delta("Первое предложение про погоду.")
        host.releaseSynthesis()
        host.playback.finishCurrent()
        assertEquals("пауза внутри хода — ещё не конец", 0, host.answerFinished)

        // Флашить нечего: конец хода должен объявиться без ожидания сигнала очереди, иначе
        // hands-free завис бы в SPEAKING.
        host.done()
        assertEquals("нечего доигрывать — конец объявлен сразу", 1, host.answerFinished)
    }

    /**
     * Хост с управляемым синтезом: проводка повторяет [ChatActivity] один в один, включая
     * предикат конца хода (`hasPendingSpeech`), но фраза висит в SYNTHESIZING до
     * [releaseSynthesis] — состояние, в котором старый предикат ошибался.
     */
    private class DelayedTurnHost {

        val policy = VoiceTurnSpeechPolicy()
        val lifecycle = StreamSpeechLifecycle()
        val client = GatedClient()
        val playback = RecordingPlayback()
        val controller = StreamingSpeechController(
            queue = SpeechQueue(
                client = client,
                playback = playback,
                scope = CoroutineScope(Dispatchers.Unconfined),
                onDrained = { drained() },
                retryPolicy = SpeechRetryPolicy(maxAttempts = 1, maxDelayMs = 0L),
            ),
        )

        var answerFinished: Int = 0
            private set

        var finalText: String? = null
            private set

        val played: List<String> get() = playback.played

        fun beginTurn(enabled: Boolean = true) {
            lifecycle.begin(enabled)
            controller.stop()
            policy.onTurnStarted()
        }

        fun delta(text: String) {
            val speakable = policy.onGeneratedText(text) ?: return
            if (!lifecycle.acceptsDelta) return
            controller.onDelta(speakable)
        }

        fun done() {
            if (!lifecycle.isSpeechEnabled) return
            finalText = policy.onFinal("Готово: двадцать градусов.")
            controller.onTurnFinished()
            lifecycle.end()
            // То же решение, что в ChatActivity.endStreamSpeech: пустая ОЧЕРЕДЬ, а не тишина.
            if (!controller.hasPendingSpeech) drained()
        }

        fun cancel() {
            lifecycle.cancel()
            controller.stop()
        }

        /** Отпустить синтез: висевшие фразы получают аудио. */
        fun releaseSynthesis() = client.completeAll()

        private fun drained() {
            if (!lifecycle.isSpeechEnabled) return
            if (!lifecycle.onQueueDrained()) return
            answerFinished++
        }
    }

    /** Синтез, который висит в SYNTHESIZING до [completeAll]. */
    private class GatedClient : ServerTtsClient {

        private val gates = LinkedHashMap<String, CompletableDeferred<ServerTtsResult>>()

        val requested: MutableList<String> = ArrayList()

        override suspend fun synthesize(text: String): ServerTtsResult {
            requested += text
            return synchronized(gates) { gates.getOrPut(text) { CompletableDeferred() } }.await()
        }

        fun completeAll() {
            val pending = synchronized(gates) { gates.entries.map { it.key to it.value } }
            pending.forEach { (text, gate) ->
                gate.complete(ServerTtsResult.Audio(text.toByteArray(), "audio/wav"))
            }
        }
    }
}
