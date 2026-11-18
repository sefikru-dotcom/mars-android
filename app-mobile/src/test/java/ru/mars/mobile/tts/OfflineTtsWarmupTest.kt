package ru.mars.mobile.tts

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверка свободного места в [OfflineTtsWarmup]: отказ приходит ДО скачивания.
 *
 * Раньше место проверялось только в `diagnose()`, то есть ПОСЛЕ отказа `prepare()`. На
 * телефоне с 70 МиБ свободных это выглядело так: 64 МиБ скачаны, распаковка упала на
 * середине, человек получил «не удалось скачать голос». Порог при этом был равен размеру
 * архива (64 МиБ), хотя на пике нужны и архив, и распакованное дерево (≈149 МиБ).
 *
 * Здесь проверяется политика: при нехватке места `prepare()` не вызывается вовсе, причина
 * человеческая и запоминается; «неизвестно» (`<= 0`) установку не блокирует.
 */
class OfflineTtsWarmupTest {

    /** Порт, который считает вызовы: главное доказательство «до сети не дошло». */
    private class FakePort(
        private var ready: Boolean = false,
        private val prepareResult: Boolean = true,
    ) : OfflineTtsPort {
        var prepareCalls: Int = 0
            private set

        override suspend fun prepare(): Boolean {
            prepareCalls++
            if (prepareResult) ready = true
            return prepareResult
        }

        override fun speak(text: String): Boolean = false
        override fun stop() = Unit
        override fun isReady(): Boolean = ready
        override fun release() {
            ready = false
        }
    }

    private class FakeDiagnostics(
        private val network: Boolean = true,
        private val free: Long = 0L,
    ) : WarmUpDiagnostics {
        override fun hasNetwork(): Boolean = network
        override fun freeBytes(): Long = free
    }

    private val mib = 1024L * 1024L

    private fun warmup(
        port: OfflineTtsPort = FakePort(),
        free: Long = 0L,
        network: Boolean = true,
        relay: ProgressRelay = ProgressRelay(),
    ): OfflineTtsWarmup = OfflineTtsWarmup(
        port = port,
        model = null,
        diagnostics = FakeDiagnostics(network = network, free = free),
        relay = relay,
        clockNanos = { 0L },
    )

    @Test
    fun `нехватка места отсекается до вызова prepare`() = runBlocking {
        val port = FakePort()
        val result = warmup(port = port, free = 70 * mib).warmUpResult()

        assertTrue("ожидался отказ", result is WarmUpResult.Failed)
        assertEquals("до сети дело дойти не должно", 0, port.prepareCalls)
    }

    @Test
    fun `причина называет реальный пик а не размер архива`() = runBlocking {
        val result = warmup(free = 70 * mib).warmUpResult() as WarmUpResult.Failed
        val peakMb = OfflineTtsManifest.PEAK_BYTES / mib

        assertTrue(
            "в причине обязано быть «Мало свободного места»: ${result.reason}",
            result.reason.contains("Мало свободного места"),
        )
        assertTrue(
            "порог в тексте — пик установки ($peakMb МБ), а не архив: ${result.reason}",
            result.reason.contains("$peakMb МБ"),
        )
        assertTrue(
            "пик обязан быть больше архива, иначе порог снова занижен",
            OfflineTtsManifest.PEAK_BYTES > OfflineTtsManifest.ARCHIVE_SIZE,
        )
    }

    @Test
    fun `отказ по месту запоминается в lastFailure`() = runBlocking {
        val sut = warmup(free = 70 * mib)
        val result = sut.warmUpResult() as WarmUpResult.Failed

        assertEquals(result.reason, sut.lastFailure)
        assertFalse(sut.isReady())
    }

    @Test
    fun `повторный вызов в паузе не качает и не поднимает движок`() = runBlocking {
        val port = FakePort()
        val sut = warmup(port = port, free = 70 * mib)

        sut.warmUp()
        sut.warmUp()

        assertEquals("пауза после отказа: ни одного prepare", 0, port.prepareCalls)
    }

    @Test
    fun `ровно пикового объёма хватает`() = runBlocking {
        val port = FakePort()
        val result = warmup(port = port, free = OfflineTtsManifest.PEAK_BYTES).warmUpResult()

        assertTrue("при точном запасе отказ недопустим: ${result.reason}", result is WarmUpResult.Ready)
        assertEquals(1, port.prepareCalls)
    }

    @Test
    fun `неизвестный размер места установку не блокирует`() = runBlocking {
        // `-1` — «неизвестно» (ФС не ответила). Обвинять место без данных нельзя.
        val port = FakePort()
        val result = warmup(port = port, free = -1L).warmUpResult()

        assertTrue(result is WarmUpResult.Ready)
        assertEquals(1, port.prepareCalls)
    }

    @Test
    fun `ноль от заглушки не считается нехваткой места`() = runBlocking {
        // `unitTests.isReturnDefaultValues` отдаёт 0 из StatFs. Если трактовать ноль как
        // «места нет», проверка заблокировала бы установку в каждом тесте и на устройстве
        // с неизвестным размером.
        val port = FakePort()
        val result = warmup(port = port, free = 0L).warmUpResult()

        assertTrue(result is WarmUpResult.Ready)
        assertEquals(1, port.prepareCalls)
    }

    @Test
    fun `экран не получает прогресс когда места нет`() = runBlocking {
        // «Готовлю голос… 0 %» при отказе до скачивания — ложь для человека.
        val relay = ProgressRelay()
        val events = mutableListOf<Pair<Long, Long>>()
        relay.attach { done, total -> events += done to total }

        warmup(free = 70 * mib, relay = relay).warmUpResult()

        assertTrue("прогресса быть не должно: $events", events.isEmpty())
    }

    @Test
    fun `движок не поднялся — причина про движок а не про место`() = runBlocking {
        // Места достаточно, prepare() вернул false: место в причине фигурировать не должно.
        val result = warmup(port = FakePort(prepareResult = false), free = 500 * mib)
            .warmUpResult() as WarmUpResult.Failed

        assertFalse(
            "место ни при чём: ${result.reason}",
            result.reason.contains("Мало свободного места"),
        )
        assertTrue(
            "причина обязана быть про скачивание/целостность: ${result.reason}",
            result.reason.contains("Не удалось скачать голос"),
        )
    }
}
