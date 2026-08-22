package de.lazuli.api.tweaks;

/**
 * One constant per tweak shipped by the Tweaks feature (spec Public API).
 *
 * <p>Deliberately {@code KeyBinding}-free: {@code :api} has no Minecraft-jar
 * dependency (confirmed at implementation time -- {@code api/} has no
 * {@code build.gradle} of its own beyond the root project's plain
 * {@code java-library} convention, unlike {@code common/build.gradle}), so
 * hotkey binding objects live entirely platform-side (see
 * {@code TweakDefinition}'s own Javadoc for the full explanation of this
 * deviation from the spec's literal {@code TweakDefinition.keyBinding():
 * KeyBinding} signature).
 */
public enum TweakId {
    ANTI_DROP,
    FORCE_BRIGHTNESS,
    CHAT_FILTER,
    CHAT_PLAYER_HEADS,
    CUSTOM_CROSSHAIR,
    DISABLE_ANIMATIONS,
    DISABLE_PARTICLES,
    HIDE_PLAYER_NAMES,
    CLEAR_WATER,
    DISABLE_COSMETICS,
    ZOOM,
    DISABLE_BOSS_BARS,
    NO_RAIN,
    FREECAM,
    COMPASS
}
