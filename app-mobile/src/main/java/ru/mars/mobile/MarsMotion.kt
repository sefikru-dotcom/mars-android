package ru.mars.mobile

/**
 * Motion tokens for the Mars mobile client (pure JVM, no Android classes).
 *
 * Design stance: the app is deliberately calm. Motion exists to explain state changes
 * (something appeared, something expanded, the drawer moved) — never to decorate.
 * Therefore:
 * - short durations only (no effect may exceed [slowMs]);
 * - the standard curve is [INTERPOLATOR_DECELERATE]: elements start quickly and settle,
 *   which reads as responsive without feeling playful;
 * - bounce/overshoot/anticipation curves are banned outright ([INTERPOLATOR_BOUNCE] and
 *   [INTERPOLATOR_OVERSHOOT] exist only as forbidden identifiers for lint-style checks);
 * - no infinite/decorative looping animations;
 * - every consumer must route durations through [MotionPolicy.durationFor] so that the
 *   system "reduce motion" accessibility setting collapses everything to instant state
 *   changes.
 *
 * The interpolator constants are plain string identifiers resolved at the view layer
 * (e.g. android.view.animation.PathInterpolator / DecelerateInterpolator), which keeps
 * this file unit-testable on the JVM.
 */
object MarsMotion {

    /** Micro-feedback and small reveals: tap ripples, the "scroll to bottom" FAB. */
    const val fastMs: Int = 120

    /** Standard content motion: message reveal, card expand, network banner. */
    const val baseMs: Int = 200

    /** Large surface motion: navigation drawer slide. Never longer than this. */
    const val slowMs: Int = 300

    /** Standard curve: fast start, gentle settle. Default for every transition. */
    const val INTERPOLATOR_DECELERATE: String = "decelerate"

    /** Reserved for content that is physically leaving the screen (fade + collapse). */
    const val INTERPOLATOR_ACCELERATE: String = "accelerate"

    /** Forbidden: bounce violates the calm-motion stance; kept for guard checks only. */
    const val INTERPOLATOR_BOUNCE: String = "bounce"

    /** Forbidden: overshoot violates the calm-motion stance; kept for guard checks only. */
    const val INTERPOLATOR_OVERSHOOT: String = "overshoot"

    /** Interpolators that must never be used in this app. */
    val FORBIDDEN_INTERPOLATORS: Set<String> = setOf(INTERPOLATOR_BOUNCE, INTERPOLATOR_OVERSHOOT)

    /** True when [interpolator] is allowed by the motion stance. */
    fun isAllowedInterpolator(interpolator: String): Boolean = interpolator !in FORBIDDEN_INTERPOLATORS
}
