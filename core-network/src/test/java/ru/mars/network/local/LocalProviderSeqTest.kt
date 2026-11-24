package ru.mars.network.local

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Метка конфигурации провайдера и остановка устаревшего шага (B1).
 *
 * Проверяется ровно то, что обещано человеку: пока шаг идёт, он принадлежит той
 * конфигурации, с которой начался. Смена адреса/модели посреди шага прекращает его
 * события, и прекращает МОЛЧА — без `LocalChatEvent.Error`, потому что адрес сменил сам
 * человек, а не сломался сервер.
 *
 * Тест не ходит в сеть: он проверяет механизм ([LocalProviderStamp] + [emitIfCurrent]),
 * которым пользуется транспорт. Сквозная проверка на живом сокете — в
 * `LocalProviderOvertakeStreamTest`.
 */
class LocalProviderSeqTest {

    @Test
    fun bumpMakesPreviouslyTakenStampOvertaken() {
        val seq = LocalProviderSeq()
        val stamp = seq.snapshot()

        assertFalse("до смены шаг актуален", stamp.isOvertaken)
        assertFalse(seq.isOvertaken(stamp.token))

        seq.bump()

        assertTrue("после смены шаг устарел", stamp.isOvertaken)
        assertTrue(seq.isOvertaken(stamp.token))
    }

    @Test
    fun freshStampIsCurrentAfterBump() {
        // Новый шаг, начатый ПОСЛЕ смены, обязан быть актуальным: иначе смена настроек
        // навсегда замолчала бы чат.
        val seq = LocalProviderSeq()
        seq.bump()
        seq.bump()

        assertFalse(seq.snapshot().isOvertaken)
    }

    @Test
    fun seqGrowsMonotonicallyAndNeverRepeats() {
        val seq = LocalProviderSeq()
        val seen = mutableSetOf<Long>()
        var previous = seq.readSeq()
        repeat(100) {
            val next = seq.bump()
            assertTrue("метка обязана расти: $previous → $next", next > previous)
            assertTrue("метка не должна повторяться", seen.add(next))
            previous = next
        }
        assertEquals("readSeq отдаёт последнюю метку", previous, seq.readSeq())
    }

    @Test
    fun overtakeMidStreamStopsEmissionAfterTheChange() = runBlocking {
        val seq = LocalProviderSeq()
        val stamp = seq.snapshot()
        val source = listOf(
            LocalChatEvent.Delta("1"),
            LocalChatEvent.Delta("2"),
            LocalChatEvent.Delta("3"),
        )

        val delivered = flow<LocalChatEvent> {
            source.forEachIndexed { index, event ->
                if (!emitIfCurrent(stamp, event)) return@flow
                // Смену настроек изображаем сразу после первого кадра — как это и бывает:
                // человек жмёт «другая модель», пока ответ ещё печатается.
                if (index == 0) seq.bump()
            }
        }.toList()

        assertEquals("после смены метки событий быть не должно", listOf(LocalChatEvent.Delta("1")), delivered)
    }

    @Test
    fun streamWithoutOvertakeIsComplete() = runBlocking {
        val seq = LocalProviderSeq()
        val stamp = seq.snapshot()
        val source = listOf(
            LocalChatEvent.Delta("1"),
            LocalChatEvent.Delta("2"),
            LocalChatEvent.AssistantFinished("12", emptyList(), "stop"),
        )

        val delivered = flow<LocalChatEvent> {
            for (event in source) {
                if (!emitIfCurrent(stamp, event)) return@flow
            }
        }.toList()

        assertEquals("без смены метки поток обязан дойти целиком", source, delivered)
    }

    @Test
    fun overtakenStampSuppressesEvenErrorEvents() = runBlocking {
        // Отдельно про ошибку: именно её нельзя показывать после смены адреса — человек
        // искал бы сбой сети там, где сам переключил провайдера.
        val seq = LocalProviderSeq()
        val stamp = seq.snapshot()
        seq.bump()

        val delivered = flow<LocalChatEvent> {
            if (!emitIfCurrent(stamp, LocalChatEvent.Error("сеть: обрыв", retryable = true))) return@flow
        }.toList()

        assertTrue("устаревший шаг обязан молчать, а не пугать ошибкой", delivered.isEmpty())
    }
}
