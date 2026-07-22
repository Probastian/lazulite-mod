package de.lazuli.api.mainmenu;

/**
 * One frame's worth of {@code IdleCharacterAnimator}'s idle-animation output
 * (spec FR8.6) -- the pure, plain-data hand-off between the
 * {@code services}-layer animation math and a platform Version Adapter's
 * {@code ModelPart}-posing code, so the adapter never needs to know the
 * animation math itself (spec Public API item 1).
 *
 * <p>Usage example (Version Adapter posing code, illustrative):
 * <pre>{@code
 * CharacterPose pose = animator.poseAt(elapsedSeconds);
 * body.y = (float) pose.bobOffset();
 * rightArm.xRot = (float) pose.armSwingAngle();
 * leftLeg.xRot = (float) -pose.legSwayAngle();
 * }</pre>
 *
 * @param bobOffset      whole-body vertical bob offset, in the same
 *                       unit-equivalent scale as the design doc's
 *                       "&plusmn;6px-equivalent" figure (spec FR8.6)
 * @param armSwingAngle  arm-swing angle, in radians
 * @param legSwayAngle   leg-sway angle, in radians
 */
public record CharacterPose(double bobOffset, double armSwingAngle, double legSwayAngle) {
}
