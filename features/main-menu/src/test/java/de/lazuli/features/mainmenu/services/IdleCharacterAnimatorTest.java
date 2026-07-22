package de.lazuli.features.mainmenu.services;

import de.lazuli.api.mainmenu.CharacterPose;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdleCharacterAnimatorTest {

    private final IdleCharacterAnimator animator = new IdleCharacterAnimator();

    @Test
    void allThreeComponentsAreNonzeroAtSomeSample() {
        CharacterPose pose = animator.poseAt(0.5);
        assertThat(pose.bobOffset()).isNotZero();
        assertThat(pose.armSwingAngle()).isNotZero();
        assertThat(pose.legSwayAngle()).isNotZero();
    }

    @Test
    void componentsVaryIndependentlyAcrossTimeSamples() {
        CharacterPose t0 = animator.poseAt(0.13);
        CharacterPose t1 = animator.poseAt(0.47);
        CharacterPose t2 = animator.poseAt(1.01);

        assertThat(t0.bobOffset()).isNotEqualTo(t1.bobOffset());
        assertThat(t1.bobOffset()).isNotEqualTo(t2.bobOffset());

        assertThat(t0.armSwingAngle()).isNotEqualTo(t1.armSwingAngle());
        assertThat(t1.armSwingAngle()).isNotEqualTo(t2.armSwingAngle());

        assertThat(t0.legSwayAngle()).isNotEqualTo(t1.legSwayAngle());
        assertThat(t1.legSwayAngle()).isNotEqualTo(t2.legSwayAngle());
    }

    @Test
    void bobStaysWithinItsDocumentedAmplitude() {
        for (double t = 0.0; t < 5.0; t += 0.05) {
            assertThat(animator.poseAt(t).bobOffset()).isBetween(
                    -IdleCharacterAnimator.BOB_AMPLITUDE, IdleCharacterAnimator.BOB_AMPLITUDE);
        }
    }

    @Test
    void bobIsContinuousAcrossItsOwnLoopBoundary() {
        double atStart = animator.poseAt(0.0).bobOffset();
        double atOnePeriodLater = animator.poseAt(IdleCharacterAnimator.BOB_PERIOD_SECONDS).bobOffset();
        assertThat(atOnePeriodLater).isCloseTo(atStart, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void armSwingIsContinuousAcrossItsOwnLoopBoundary() {
        double atStart = animator.poseAt(0.0).armSwingAngle();
        double atOnePeriodLater = animator.poseAt(IdleCharacterAnimator.ARM_SWING_PERIOD_SECONDS).armSwingAngle();
        assertThat(atOnePeriodLater).isCloseTo(atStart, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void legSwayIsContinuousAcrossItsOwnLoopBoundary() {
        double atStart = animator.poseAt(0.0).legSwayAngle();
        double atOnePeriodLater = animator.poseAt(IdleCharacterAnimator.LEG_SWAY_PERIOD_SECONDS).legSwayAngle();
        assertThat(atOnePeriodLater).isCloseTo(atStart, org.assertj.core.data.Offset.offset(1e-9));
    }
}
