package de.lazuli.tweaks;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Tweaks spec T6 (Disable Animations): {@code SpriteContents$Animator} (Yarn)
 * has no back-reference field to its owning {@code SpriteContents}/{@code
 * Identifier} (confirmed via {@code javap -p} -- see
 * {@code docs/specs/tweaks-hooks-wiring-plan.md}'s Risks section, "T6
 * animatedTextureId back-reference"). This registry implements the plan's
 * documented fallback: a companion {@code @Inject} at {@code
 * SpriteContents.createAnimator(...)}'s return site stashes the owning
 * sprite id keyed by the created {@code Animator} instance (weakly, so it
 * never outlives the sprite/animator pair), which {@code Animator.tick()}'s
 * own mixin then looks up.
 */
public final class SpriteAnimationRegistry {

    private static final Map<Object, String> ID_BY_STATE = Collections.synchronizedMap(new WeakHashMap<>());

    private SpriteAnimationRegistry() {
    }

    public static void register(Object animationState, String spriteId) {
        ID_BY_STATE.put(animationState, spriteId);
    }

    public static String idOf(Object animationState) {
        return ID_BY_STATE.get(animationState);
    }
}
