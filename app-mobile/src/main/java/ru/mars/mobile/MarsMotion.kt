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
 * EXCEPTION — live-state indicators may pulse (bounded by [MarsPulsePolicy]).
 * A status dot that says "the turn is running" or "the node is online" carries state,
 * not decoration: it is the pulsing that distinguishes "working" from "stuck". The
 * exception is deliberately narrow and must not be widened:
 * - only the halo alpha of [MarsTheme.glowingStatusDot] moves — never geometry, size,
 *   position, scale or text;
 * - period and amplitude live in [MarsPulsePolicy] (>= 1200 ms, halo alpha 0.25..0.65),
 *   NOT here: these are cycles and [MotionCue]/[MotionPolicy] describe TRANSITIONS with a
 *   [slowMs] ceiling. Routing a pulse through [MotionPolicy.durationFor] would collapse a
 *   3 s cycle to 0 and turn a calm breath into a strobe;
 * - pulses obey the same escape hatches (animators disabled, power save, reduce motion)
 *   and fall back to a static dot via [MarsPulsePolicy.modeFor].
 * Decorative looping animations remain banned; this is the only permitted loop.
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
