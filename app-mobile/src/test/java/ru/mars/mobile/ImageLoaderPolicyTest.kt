package ru.mars.mobile

import org.junit.Assert.*
import org.junit.Test

/**
 * JVM tests for the [ImageLoader] memory policies: byte-based cache budget, decode target
 * resolution, power-of-two downsampling and the download cap.
 */
class ImageLoaderPolicyTest {

    @Test fun cacheBudgetIsClampedToCeilingOnHugeHeaps() {
        // 256MB heap → 1/8 = 32MB is above the ceiling → clamped to MAX.
        assertEquals(ImageLoaderPolicy.MAX_CACHE_BYTES, ImageLoaderPolicy.cacheBudgetBytes(256L * 1024 * 1024))
        val huge = 4L * 1024 * 1024 * 1024
        assertEquals(ImageLoaderPolicy.MAX_CACHE_BYTES, ImageLoaderPolicy.cacheBudgetBytes(huge))
    }

    @Test fun cacheBudgetIsClampedToFloorOnTinyHeaps() {
        val tiny = 4L * 1024 * 1024 // 4MB heap → 1/8 = 512KB, below the floor
        assertEquals(ImageLoaderPolicy.MIN_CACHE_BYTES, ImageLoaderPolicy.cacheBudgetBytes(tiny))
    }

    @Test fun cacheBudgetScalesWithReasonableHeap() {
        val heap = 128L * 1024 * 1024 // 128MB/8 = 16MB — inside [min, max]
        assertEquals(16 * 1024 * 1024, ImageLoaderPolicy.cacheBudgetBytes(heap))
    }

    @Test fun cacheBudgetIsAlwaysWithinSafeRange() {
        for (heap in listOf(0L, 1L, 1024L, 64L * 1024 * 1024, 8L * 1024 * 1024 * 1024)) {
            val budget = ImageLoaderPolicy.cacheBudgetBytes(heap)
            assertTrue(budget >= ImageLoaderPolicy.MIN_CACHE_BYTES)
            assertTrue(budget <= ImageLoaderPolicy.MAX_CACHE_BYTES)
        }
    }

    @Test fun targetSizeUsesLaidOutViewEdge() {
        assertEquals(132, ImageLoaderPolicy.targetSizePx(132, 132))
        assertEquals(512, ImageLoaderPolicy.targetSizePx(512, 300))
    }

    @Test fun targetSizeFallsBackWhenViewNotLaidOut() {
        assertEquals(ImageLoaderPolicy.DEFAULT_TARGET_PX, ImageLoaderPolicy.targetSizePx(0, 0))
        assertEquals(ImageLoaderPolicy.DEFAULT_TARGET_PX, ImageLoaderPolicy.targetSizePx(-1, 0))
        assertEquals(64, ImageLoaderPolicy.targetSizePx(0, 0, fallbackPx = 64))
    }

    @Test fun targetSizeIsClamped() {
        assertEquals(ImageLoaderPolicy.MAX_TARGET_PX, ImageLoaderPolicy.targetSizePx(5000, 5000))
        assertEquals(1, ImageLoaderPolicy.targetSizePx(0, 0, fallbackPx = -5))
    }

    @Test fun sampleSizeDownsamplesByPowerOfTwoToCoverTarget() {
        // 1000x1000 → target 256: /2=500 ok, /4=250 < 256 → sample 2.
        assertEquals(2, ImageLoaderPolicy.sampleSize(1000, 1000, 256))
        // 512x512 → target 256: /2=256 >= 256 ok, /4=128 < 256 → sample 2.
        assertEquals(2, ImageLoaderPolicy.sampleSize(512, 512, 256))
        // 2048x2048 → target 256: /8=256 ok → sample 8.
        assertEquals(8, ImageLoaderPolicy.sampleSize(2048, 2048, 256))
    }

    @Test fun sampleSizeNeverUpsamples() {
        assertEquals(1, ImageLoaderPolicy.sampleSize(100, 100, 256))
        assertEquals(1, ImageLoaderPolicy.sampleSize(256, 256, 256))
    }

    @Test fun sampleSizeHandlesUnknownInputs() {
        assertEquals(1, ImageLoaderPolicy.sampleSize(-1, 100, 256))
        assertEquals(1, ImageLoaderPolicy.sampleSize(100, 0, 256))
        assertEquals(1, ImageLoaderPolicy.sampleSize(1000, 1000, 0))
    }

    @Test fun sampleSizeUsesSmallerAxisAsLimitingFactor() {
        // Wide panorama 4000x300 → target 256: height /2 = 150 < 256 → sample stays 1.
        assertEquals(1, ImageLoaderPolicy.sampleSize(4000, 300, 256))
    }

    @Test fun downloadCapRejectsOversizedContentLength() {
        assertFalse(ImageLoaderPolicy.acceptDownload(ImageLoaderPolicy.MAX_DOWNLOAD_BYTES + 1))
        assertTrue(ImageLoaderPolicy.acceptDownload(ImageLoaderPolicy.MAX_DOWNLOAD_BYTES))
    }

    @Test fun downloadCapAllowsUnknownContentLength() {
        assertTrue(ImageLoaderPolicy.acceptDownload(-1))
        assertTrue(ImageLoaderPolicy.acceptDownload(0))
    }
}
