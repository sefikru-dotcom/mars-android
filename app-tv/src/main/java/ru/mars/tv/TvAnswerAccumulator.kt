package ru.mars.tv

/**
 * Pure accumulator for a streaming TV answer bubble.
 *
 * The answer bubble starts life as a "Марс: " placeholder; the streaming chunks are plain
 * answer text. The placeholder is dropped exactly ONCE — at the first chunk — and every
 * chunk after that is appended verbatim. The previous inline implementation re-applied
 * `removePrefix("Марс: ")` to the accumulated text on EVERY chunk, so an answer that itself
 * began with «Марс: » lost that prefix. Keeping this state machine out of the Activity
 * makes the once-only reset testable on the JVM.
 */
class TvAnswerAccumulator(placeholder: String) {
    private val builder = StringBuilder(placeholder)
    private var placeholderRemoved = false

    /** Consumes one stream chunk and returns the full accumulated answer text. */
    fun onChunk(chunk: String): String {
        if (!placeholderRemoved) {
            builder.setLength(0)
            placeholderRemoved = true
        }
        builder.append(chunk)
        return builder.toString()
    }
}
