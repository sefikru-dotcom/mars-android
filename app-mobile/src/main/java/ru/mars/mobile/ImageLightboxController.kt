package ru.mars.mobile

/**
 * Immutable description of one viewable image: absolute [url], transcript [caption]
 * and the [messageIndex] of the bubble it came from (-1 when unknown / attachment rail).
 * Built via [ImageLightboxSpec.from] which enforces the [ImageLightboxPolicy] contract.
 */
data class ImageLightboxSpec(
    val url: String,
    val caption: String,
    val messageIndex: Int = -1,
) {
    companion object {
        /**
         * Spec from a raw URL (inline markdown image or attachment path): null when the
         * value is empty, carries a non-http scheme, or does not look like an image —
         * the lightbox must never open on such links (they go to the external browser).
         * Caption: markdown alt when present, otherwise the file name without extension,
         * otherwise the neutral «Изображение» ([ImageLightboxPolicy.inlineLabel]).
         */
        fun from(
            rawUrl: String?,
            baseUrl: String = ThumbnailUrlResolver.DEFAULT_ORIGIN,
            alt: String? = null,
            messageIndex: Int = -1,
        ): ImageLightboxSpec? {
            val resolved = ImageLightboxPolicy.resolveUrl(rawUrl, baseUrl) ?: return null
            if (!ImageLightboxPolicy.isImageUrl(resolved)) return null
            return ImageLightboxSpec(resolved, captionFor(alt, resolved), messageIndex)
        }

        /** Caption extraction: explicit alt wins, then the URL file name, then the neutral label. */
        fun captionFor(alt: String?, url: String): String {
            alt?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            val file = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
            val name = file.substringBeforeLast('.').replace('_', ' ').trim()
            return name.ifEmpty { ImageLightboxPolicy.inlineLabel(null) }
        }
    }
}

/**
 * Pure state holder of the fullscreen image viewer (lightbox): which spec is open,
 * zoom level, prev/next navigation across a gallery and the dismiss guard. The Activity
 * only renders [current]/[zoom] and forwards gestures — all decisions live here,
 * JVM unit-testable (no Android imports).
 */
class ImageLightboxController {

    private var items: List<ImageLightboxSpec> = emptyList()
    private var index = -1

    /** Current zoom scale; always within [MIN_ZOOM]..[MAX_ZOOM]. */
    var zoom: Float = MIN_ZOOM
        private set

    /** True while a spec is open. */
    val isOpen: Boolean get() = index in items.indices

    /** The visible spec, or null when the lightbox is closed. */
    val current: ImageLightboxSpec? get() = items.getOrNull(index)

    /** Number of images in the open gallery (0 when closed). */
    val size: Int get() = if (isOpen) items.size else 0

    /** Position of [current] inside the gallery (-1 when closed). */
    val position: Int get() = if (isOpen) index else -1

    /** True when zoomed in past the resting scale (drives the dismiss guard). */
    val isZoomed: Boolean get() = zoom > MIN_ZOOM + ZOOM_EPSILON

    /**
     * Opens the gallery at [startIndex] (clamped into range). False on an empty list —
     * the lightbox never opens without a spec. Zoom resets to resting on every open.
     */
    fun open(specs: List<ImageLightboxSpec>, startIndex: Int = 0): Boolean {
        if (specs.isEmpty()) return false
        items = specs
        index = startIndex.coerceIn(0, specs.lastIndex)
        zoom = MIN_ZOOM
        return true
    }

    /** Opens a single image. */
    fun open(spec: ImageLightboxSpec): Boolean = open(listOf(spec))

    fun zoomIn() {
        zoom = (zoom + ZOOM_STEP).coerceAtMost(MAX_ZOOM)
    }

    fun zoomOut() {
        zoom = (zoom - ZOOM_STEP).coerceAtLeast(MIN_ZOOM)
    }

    /** Pinch-zoom result, clamped into the allowed range. */
    fun setZoom(value: Float) {
        zoom = if (value.isNaN()) MIN_ZOOM else value.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    fun resetZoom() {
        zoom = MIN_ZOOM
    }

    /** Next gallery image (wraps around). False when closed or the gallery is a single item. */
    fun next(): Boolean = step(1)

    /** Previous gallery image (wraps around). False when closed or the gallery is a single item. */
    fun previous(): Boolean = step(-1)

    private fun step(delta: Int): Boolean {
        if (!isOpen || items.size < 2) return false
        index = ((index + delta) % items.size + items.size) % items.size
        zoom = MIN_ZOOM // navigation always lands on the resting scale
        return true
    }

    /**
     * Dismiss guard for the back gesture: while zoomed, the first press only collapses
     * the zoom and is consumed (false); at resting scale it closes the viewer (true).
     * False when already closed — nothing to dismiss.
     */
    fun onBackPressed(): Boolean {
        if (!isOpen) return false
        if (isZoomed) {
            resetZoom()
            return false
        }
        dismiss()
        return true
    }

    /** Closes the viewer and drops the gallery (zoom resets, [current] becomes null). */
    fun dismiss() {
        items = emptyList()
        index = -1
        zoom = MIN_ZOOM
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 4f
        const val ZOOM_STEP = 0.5f

        /** Floating-point tolerance so 1.0000001 after pinch math is still «at rest». */
        private const val ZOOM_EPSILON = 1e-3f
    }
}
