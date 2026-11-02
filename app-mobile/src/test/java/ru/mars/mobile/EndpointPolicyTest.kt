package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Смысловой конец реплики: тишина + падение уровня + целостность фразы.
 *
 * Тест держит именно те правила, ради которых политика появилась: не рвать фразу на
 * запятой и не ждать лишний слот после падения голоса. Каждая проверка сформулирована
 * через то, что человек реально делает за секунду молчания.
 */
class EndpointPolicyTest {

    private fun features(
        silenceMs: Long,
        lastRms: Int = 1_000,
        plateauRms: Int = 1_000,
        words: String = "",
    ) = EndpointFeatures(
        silenceMs = silenceMs,
        lastSpeechRms = lastRms,
        averageSpeechRms = plateauRms,
        lastWords = words,
    )

    @Test
    fun `жёсткий предел закрывает реплику даже с оборванной фразой`() {
        // 1.7 с молчания — дальше ждать неприлично, даже если текст кончился на «и».
        val decision = EndpointPolicy.decide(
            features(EndpointPolicy.HARD_SILENCE_MS + 100, words = "я купил хлеб и"),
        )
        assertEquals(EndpointDecision.END_UTTERANCE, decision)
    }

    @Test
    fun `оборванная фраза ждёт продолжения`() {
        val decision = EndpointPolicy.decide(features(900, words = "Сколько стоит билет и"))
        assertEquals(EndpointDecision.KEEP_LISTENING, decision)
    }

    @Test
    fun `запятая в конце тоже считается оборванной фразой`() {
        assertEquals(
            EndpointDecision.KEEP_LISTENING,
            EndpointPolicy.decide(features(700, words = "Сколько стоит билет,")),
        )
        assertEquals(
            EndpointDecision.KEEP_LISTENING,
            EndpointPolicy.decide(features(700, words = "Слушай —")),
        )
    }

    @Test
    fun `запятая в СЕРЕДИНЕ фразы не делает её оборванной`() {
        // Главный риск регулярки по последнему символу: «Сколько стоит, билет» — цельная
        // мысль, и ждать после неё продолжения значило бы вешать диалог на каждой паузе.
        assertFalse(EndpointPolicy.looksIncomplete("Сколько стоит, билет"))
        assertEquals(
            EndpointDecision.END_UTTERANCE,
            EndpointPolicy.decide(features(1_300, words = "Сколько стоит, билет")),
        )
    }

    @Test
    fun `штатный hangover закрывает цельную фразу`() {
        assertEquals(
            EndpointDecision.END_UTTERANCE,
            EndpointPolicy.decide(features(1_200, words = "Сколько стоит билет")),
        )
    }

    @Test
    fun `короткая пауза с падением уровня закрывает фразу раньше`() {
        // 600 мс тишины и голос просел до 40 % плато — человек закончил мысль.
        val decision = EndpointPolicy.decide(
            features(600, lastRms = 400, plateauRms = 1_000, words = "Сколько стоит билет"),
        )
        assertEquals(EndpointDecision.END_UTTERANCE, decision)
    }

    @Test
    fun `короткая пауза без падения уровня — ещё слушаем`() {
        // Тот же текст, та же тишина, но уровень держится: человек думает вслух.
        val decision = EndpointPolicy.decide(
            features(600, lastRms = 1_000, plateauRms = 1_000, words = "Сколько стоит билет"),
        )
        assertEquals(EndpointDecision.KEEP_LISTENING, decision)
    }

    @Test
    fun `слишком короткая пауза не закрывает ничего`() {
        assertEquals(
            EndpointDecision.KEEP_LISTENING,
            EndpointPolicy.decide(features(300, lastRms = 100, plateauRms = 1_000, words = "да")),
        )
    }

    @Test
    fun `без текста работают только уровни`() {
        // Потоковый ASR мог не отдать ни слова: пустой текст не должен считаться
        // «оборванной фразой», иначе диалог ждал бы всегда.
        assertFalse(EndpointPolicy.looksIncomplete(""))
        assertEquals(
            EndpointDecision.END_UTTERANCE,
            EndpointPolicy.decide(features(1_300, words = "")),
        )
        assertEquals(
            EndpointDecision.KEEP_LISTENING,
            EndpointPolicy.decide(features(600, lastRms = 900, plateauRms = 1_000, words = "")),
        )
    }

    @Test
    fun `уверенность растёт с тишиной`() {
        val short = EndpointPolicy.confidence(features(400))
        val middle = EndpointPolicy.confidence(features(900))
        val long = EndpointPolicy.confidence(features(1_400))
        assertTrue(short < middle)
        assertTrue(middle < long)
    }

    @Test
    fun `уверенность падает на оборванной фразе`() {
        val whole = EndpointPolicy.confidence(features(900, words = "Сколько стоит билет"))
        val broken = EndpointPolicy.confidence(features(900, words = "Сколько стоит билет и"))
        assertTrue(broken < whole)
    }

    @Test
    fun `уверенность всегда в границах`() {
        val extreme = EndpointPolicy.confidence(
            features(60_000, lastRms = 0, plateauRms = 3_000, words = "и"),
        )
        assertTrue(extreme in 0f..1f)
        assertTrue(EndpointPolicy.confidence(features(0)) in 0f..1f)
    }

    @Test
    fun `причина решения не содержит текста реплики`() {
        // Приватность: строка уходит в лог, и текста человека в ней быть не должно.
        val words = "секретный вопрос про зарплату"
        val reason = EndpointPolicy.reason(features(900, words = words), EndpointDecision.KEEP_LISTENING)
        assertFalse(reason.contains("зарплату"))
        assertFalse(reason.contains(words))
        assertTrue(reason.isNotBlank())
    }

    @Test
    fun `нулевой hangover закрывает реплику на любой паузе`() {
        // Явное «не ждать тишины вообще» — законная настройка (скороговорка, команды),
        // и политика обязана её уважать, а не подменять своим дефолтом.
        assertEquals(
            EndpointDecision.END_UTTERANCE,
            EndpointPolicy.decide(features(0), hangoverMs = 0),
        )
    }

    @Test
    fun `отрицательные значения приводятся к нулю, а не ломают решение`() {
        // Опечатка в настройке не должна ни падать, ни давать неопределённость: отрицательная
        // тишина — это ноль, отрицательный hangover — тоже ноль.
        assertEquals(
            EndpointDecision.KEEP_LISTENING,
            EndpointPolicy.decide(features(-500), hangoverMs = 1_200),
        )
        assertEquals(
            EndpointDecision.END_UTTERANCE,
            EndpointPolicy.decide(features(-500), hangoverMs = -100),
        )
    }
}
