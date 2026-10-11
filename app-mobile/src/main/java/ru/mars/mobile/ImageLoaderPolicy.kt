package ru.mars.mobile

/**
 * Pure, JVM-testable policies behind [ImageLoader] (music thumbnails).
 *
 * The loader itself is Android-bound (LruCache/BitmapFactory), so every decision that can be
 * expressed as numbers lives here: the byte-based cache budget, the decode target size and the
 * downsampling factor. Keeping them pure means the OOM-safety rules are covered by unit tests
 * instead of instrumented ones.
 */
object ImageLoaderPolicy {

    /** Floor for the bitmap cache: below this thumbnails thrash on every scroll. */
    const val MIN_CACHE_BYTES: Int = 2 * 1024 * 1024

    /**
     * Ceiling for the bitmap cache. A process-wide singleton must stay polite: even on a
     * 512 MB heap we never take more than 24 MB for thumbnails.
     */
    const val MAX_CACHE_BYTES: Int = 24 * 1024 * 1024

    /** Fraction of the process heap the thumbnail cache may occupy. */
    const val CACHE_MEMORY_FRACTION: Int = 8

    /**
     * Hard cap on a single image download. Album art is a few hundred KB; anything above
     * this is a wrong URL (HTML error page chain, video file) and must not reach the heap.
     */
    const val MAX_DOWNLOAD_BYTES: Long = 8L * 1024 * 1024

    /** Fallback decode target when the ImageView is not laid out yet (width/height == 0). */
    const val DEFAULT_TARGET_PX: Int = 256

    /** Absolute fallback decode target; guards against absurd computed sizes. */
    const val MAX_TARGET_PX: Int = 1024

    /**
     * Byte budget for the LruCache: 1/[CACHE_MEMORY_FRACTION] of the process max heap,
     * clamped into [[MIN_CACHE_BYTES], [MAX_CACHE_BYTES]]. Never zero, never the whole heap.
     */
    fun cacheBudgetBytes(maxMemoryBytes: Long): Int {
        val share = maxMemoryBytes / CACHE_MEMORY_FRACTION
        return share.coerceIn(MIN_CACHE_BYTES.toLong(), MAX_CACHE_BYTES.toLong()).toInt()
    }

    /**
     * Decode target (square edge, px) for a view of [viewWidth]x[viewHeight]. Unlaid-out
     * views (0) fall back to [fallbackPx]; the result is clamped to [MAX_TARGET_PX] and
     * never below 1.
     */
    fun targetSizePx(viewWidth: Int, viewHeight: Int, fallbackPx: Int = DEFAULT_TARGET_PX): Int {
        val edge = maxOf(viewWidth, viewHeight).takeIf { it > 0 } ?: fallbackPx
        return edge.coerceIn(1, MAX_TARGET_PX)
    }

    /**
     * Power-of-two `inSampleSize` so the decoded bitmap still covers [targetPx] on both
     * axes. Unknown source dimensions (<= 0) or a source that is already small enough
     * yield 1 — we never upsample.
     */
    fun sampleSize(srcWidth: Int, srcHeight: Int, targetPx: Int): Int {
        if (srcWidth <= 0 || srcHeight <= 0 || targetPx <= 0) return 1
        var sample = 1
        while (srcWidth / (sample * 2) >= targetPx && srcHeight / (sample * 2) >= targetPx) {
            sample *= 2
        }
        return sample
    }

    /**
     * Whether a download may proceed given its advertised `Content-Length`
     * (<= 0 means unknown/chunked and is allowed — the streaming cap still applies).
     */
    fun acceptDownload(contentLength: Long): Boolean =
        contentLength <= 0 || contentLength <= MAX_DOWNLOAD_BYTES
}
