package ru.mars.tv

/**
 * Pure D-pad focus policy of the TV music screen (no Android, no I/O — JVM-testable,
 * [TvMusicNowPlayingSpec] parity).
 *
 * Вынесено из [TvMusicActivity] (декомпозиция хоста, W3): экран зовёт политику из секций,
 * а сам файл остаётся чистым — ни Android-типов, ни I/O.
 *
 * An Android TV window navigates only while some view holds focus: hiding the focused
 * view leaves `currentFocus == null` and, with no touch fallback, further D-pad presses
 * go nowhere. Both disappearing affordances of this screen are transport/state-driven:
 *
 * - «Остановить» vanishes the moment the transport goes idle — typically as the direct
 *   result of a D-pad press on the button itself ([TvMusicPlayer.stop] notifies its
 *   observers synchronously). [handoffBeforeHide] is the rule: focus must move to the
 *   stable header («Назад») BEFORE the focused affordance is hidden, never after.
 * - «Повторить» vanishes on every re-render; content and error states re-hand focus
 *   themselves, but the terminal empty state must not stay unfocused either.
 */
internal object TvMusicFocusPolicy {

    /** [View.NO_ID] mirror for JVM tests: «нет цели — фокус остаётся где был». */
    const val NO_FOCUS = -1

    /**
     * True when the focus held by a disappearing affordance must be handed to the stable
     * header first: the view is on screen, it holds focus, and this render hides it.
     * A view that stays visible (restyle) or never held focus keeps focus where it is.
     */
    fun handoffBeforeHide(viewVisible: Boolean, viewHasFocus: Boolean, staysVisible: Boolean): Boolean =
        viewVisible && viewHasFocus && !staysVisible

    /** What BACK does on the music screen: the IME absorbs the first press, the screen the next. */
    enum class BackAction { HIDE_IME, CLOSE_SCREEN }

    /**
     * BACK при открытой экранной клавиатуре закрывает ТОЛЬКО её (фокус остаётся на
     * searchInput); без клавиатуры — штатно закрывает экран.
     */
    fun backAction(imeVisible: Boolean): BackAction =
        if (imeVisible) BackAction.HIDE_IME else BackAction.CLOSE_SCREEN

    /**
     * LEFT/RIGHT focus pairs for the header buttons that are VISIBLE right now:
     * neighbours reference each other, both ends self-loop, so the D-pad never leaves
     * the row and never chases into a GONE view. Caller passes ids in visual order.
     */
    fun horizontalChain(ids: List<Int>): Map<Int, Pair<Int, Int>> {
        if (ids.isEmpty()) return emptyMap()
        return ids.mapIndexed { index, id ->
            val left = if (index == 0) ids.first() else ids[index - 1]
            val right = if (index == ids.lastIndex) ids.last() else ids[index + 1]
            id to (left to right)
        }.toMap()
    }

    /**
     * DOWN target from the search row: the first rendered track row wins; while the list
     * is empty a visible «Повторить» catches the press; otherwise there is no target
     * ([NO_FOCUS]) and focus honestly stays on the search row.
     */
    fun downTargetFromSearchRow(firstTrackRowId: Int?, retryButtonId: Int?, retryVisible: Boolean): Int =
        when {
            firstTrackRowId != null -> firstTrackRowId
            retryVisible && retryButtonId != null -> retryButtonId
            else -> NO_FOCUS
        }
}
