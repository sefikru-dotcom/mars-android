package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-тесты [TvImageLoaderPolicy] — числовые инварианты OOM-безопасности загрузчика
 * обложек [TvImageLoader]: byte-based бюджет LruCache (1/8 кучи, зажат в 2..24 МБ),
 * целевой размер декодирования, даунсэмплинг степенью двойки и лимит загрузки.
 */
class TvImageLoaderPolicyTest {

    private val mb = 1024L * 1024L

    @Test
    fun `cache budget is one eighth of the heap`() {
        assertEquals((64 * mb / 8).toInt(), TvImageLoaderPolicy.cacheBudgetBytes(64 * mb))
    }

    @Test
    fun `cache budget is clamped to 2MB floor on tiny heaps`() {
        assertEquals(TvImageLoaderPolicy.MIN_CACHE_BYTES, TvImageLoaderPolicy.cacheBudgetBytes(8 * mb))
        assertEquals(TvImageLoaderPolicy.MIN_CACHE_BYTES, TvImageLoaderPolicy.cacheBudgetBytes(0))
    }

    @Test
    fun `cache budget is clamped to 24MB ceiling on huge heaps`() {
        assertEquals(TvImageLoaderPolicy.MAX_CACHE_BYTES, TvImageLoaderPolicy.cacheBudgetBytes(512 * mb))
        assertEquals(TvImageLoaderPolicy.MAX_CACHE_BYTES, TvImageLoaderPolicy.cacheBudgetBytes(Long.MAX_VALUE))
    }

    @Test
    fun `target size uses the longer view edge and falls back when unlaid-out`() {
        assertEquals(300, TvImageLoaderPolicy.targetSizePx(300, 100))
        assertEquals(300, TvImageLoaderPolicy.targetSizePx(100, 300))
        assertEquals(TvImageLoaderPolicy.DEFAULT_TARGET_PX, TvImageLoaderPolicy.targetSizePx(0, 0))
    }

    @Test
    fun `target size is clamped to max and never below one`() {
        assertEquals(TvImageLoaderPolicy.MAX_TARGET_PX, TvImageLoaderPolicy.targetSizePx(10_000, 10_000))
        assertEquals(1, TvImageLoaderPolicy.targetSizePx(1, 1))
    }

    @Test
    fun `sample size halves by powers of two while covering the target`() {
        // 2048/8 = 256 >= 256, 2048/16 = 128 < 256 — последняя степень, ещё покрывающая цель.
        assertEquals(8, TvImageLoaderPolicy.sampleSize(2048, 2048, 256))
        assertEquals(1, TvImageLoaderPolicy.sampleSize(200, 200, 256))
    }

    @Test
    fun `sample size never upsamples and tolerates unknown bounds`() {
        assertEquals(1, TvImageLoaderPolicy.sampleSize(0, 0, 256))
        assertEquals(1, TvImageLoaderPolicy.sampleSize(-5, 100, 256))
        assertEquals(1, TvImageLoaderPolicy.sampleSize(100, 100, 0))
    }

    @Test
    fun `download accepted when content length unknown or within cap`() {
        assertTrue(TvImageLoaderPolicy.acceptDownload(-1))
        assertTrue(TvImageLoaderPolicy.acceptDownload(0))
        assertTrue(TvImageLoaderPolicy.acceptDownload(TvImageLoaderPolicy.MAX_DOWNLOAD_BYTES))
        assertFalse(TvImageLoaderPolicy.acceptDownload(TvImageLoaderPolicy.MAX_DOWNLOAD_BYTES + 1))
    }
}
