package de.lazuli.features.mainmenu.services;

import de.lazuli.api.mainmenu.CharacterPose;

/**
 * Pure, stateless idle-animation math for the 3D background's placeholder
 * character (spec FR8.6): three independent, simultaneously-looping
 * animations -- a whole-body bob, an arm swing, and a leg sway -- each a pure
 * function of elapsed time. Zero {@code net.minecraft.*}/steamworks4j import;
 * O(1) per call (spec Performance).
 *
 * <p><strong>Easing/period choices (the spec pins only the bob's own
 * &plusmn;6px-equivalent amplitude and 2.6s period, FR8.6):</strong> a plain
 * sine wave is used for all three loops -- {@code sin(2*pi*t/period)} is
 * already a smooth ease-in/ease-out curve (zero velocity at each extreme),
 * satisfying "2.6s ease-in-out" without a separate easing-curve function, and
 * is trivially continuous at every loop boundary since
 * {@code sin(2*pi*k) == sin(0) == 0} for any integer {@code k}. The arm-swing
 * and leg-sway periods/amplitudes are this class's own reasonable choice
 * (not pinned by the spec): a slightly shorter period than the bob so the
 * three loops visibly drift out of phase with one another (avoiding a
 * robotic "everything moves in lockstep" look), with a 90-degree (quarter
 * period) phase offset between the arm and the leg so they don't peak
 * together either.
 *
 * <p>Usage example:
 * <pre>{@code
 * IdleCharacterAnimator animator = new IdleCharacterAnimator();
 * CharacterPose pose = animator.poseAt(elapsedSeconds);
 * }</pre>
 */
public final class IdleCharacterAnimator {

    /** Bob loop period, in seconds (spec FR8.6, pinned). */
    public static final double BOB_PERIOD_SECONDS = 2.6;

    /** Bob amplitude, in the design doc's &plusmn;6px-equivalent unit scale (spec FR8.6, pinned). */
    public static final double BOB_AMPLITUDE = 6.0;

    /** Arm-swing loop period, in seconds (this class's own choice, documented above). */
    public static final double ARM_SWING_PERIOD_SECONDS = 1.8;

    /** Arm-swing amplitude, in radians (this class's own choice, documented above). */
    public static final double ARM_SWING_AMPLITUDE_RADIANS = 0.4;

    /** Leg-sway loop period, in seconds (this class's own choice, documented above). */
    public static final double LEG_SWAY_PERIOD_SECONDS = 2.2;

    /** Leg-sway amplitude, in radians (this class's own choice, documented above). */
    public static final double LEG_SWAY_AMPLITUDE_RADIANS = 0.3;

    /**
     * Computes the idle character's pose at a given point in time.
     *
     * @param elapsedSeconds seconds elapsed since the background renderer's
     *                       own clock started; may be any non-negative value,
     *                       called once per frame
     * @return the composed {@link CharacterPose} for this instant
     */
    public CharacterPose poseAt(double elapsedSeconds) {
        double bob = BOB_AMPLITUDE * Math.sin(2.0 * Math.PI * elapsedSeconds / BOB_PERIOD_SECONDS);
        double armSwing = ARM_SWING_AMPLITUDE_RADIANS * Math.sin(2.0 * Math.PI * elapsedSeconds / ARM_SWING_PERIOD_SECONDS);
        double legSway = LEG_SWAY_AMPLITUDE_RADIANS
                * Math.sin((2.0 * Math.PI * elapsedSeconds / LEG_SWAY_PERIOD_SECONDS) + (Math.PI / 2.0));
        return new CharacterPose(bob, armSwing, legSway);
    }
}
