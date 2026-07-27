package de.lazuli.api.tweaks;

/**
 * One static, compile-time-known tweak definition (spec F1/Public API):
 * identity, translation key, and default state.
 *
 * <p><strong>Deviation from the spec's literal Public API signature,
 * resolved before this file was written per the orchestrator's explicit
 * instruction:</strong> the spec states {@code TweakDefinition} verbatim as
 * {@code { TweakId id(); String translationKey(); TweakState defaultState();
 * KeyBinding keyBinding(); }} (plus an optional {@code secondaryKeyBinding()}
 * for Anti-Drop). Checking {@code api/build.gradle} first, as instructed,
 * found <strong>no {@code build.gradle} file at all</strong> for the
 * {@code :api} module (unlike {@code :common}, which has one declaring only
 * {@code api project(':api')}) -- {@code :api} relies solely on the root
 * project's {@code subprojects { apply plugin: 'java-library' }} block, with
 * zero Minecraft-jar/Loom dependency of any kind. Every existing type in
 * {@code api/src/main/java/de/lazuli/api/**} (e.g. {@code MainMenuTab},
 * {@code WardrobeSlot}) is correspondingly plain-Java/Minecraft-agnostic.
 *
 * <p>Adding a Minecraft dependency to this shared, multi-consumer module
 * just to type this one accessor would be a materially more invasive change
 * than the spec's wording implies (every platform module, plus
 * {@code features/tweaks}, plus any future {@code :api} consumer would
 * newly resolve a Minecraft jar transitively). Per the orchestrator's
 * explicit resolution, this interface instead stays fully Minecraft-agnostic:
 * {@link #hasSecondaryKeyBinding()} replaces {@code secondaryKeyBinding():
 * KeyBinding}, and {@code keyBinding(): KeyBinding} is dropped entirely from
 * this interface. The actual {@code KeyBinding} objects are constructed and
 * held platform-side (one {@code KeyBinding} per {@link TweakId}, plus one
 * secondary for {@code ANTI_DROP}), in each platform module's own
 * {@code TweaksClientInitializer}/keybinding-holder class -- see that
 * class's Javadoc per platform for the concrete registration/lookup surface
 * (the platform-side equivalent of the spec's
 * {@code TweakRegistry.keyBindingOf(TweakId): KeyBinding}).
 */
public interface TweakDefinition {

    TweakId id();

    /** Fully-qualified translation key, e.g. {@code "tweak.lazuli.anti_drop"}. */
    String translationKey();

    /** This tweak's default enabled flag + default configurable values. */
    TweakState defaultState();

    /**
     * Short, one-line, human-readable summary of what this tweak does,
     * rendered as the dimmer secondary line under the tweak's title in the
     * main-menu Tweaks row list (tweaks-panel-visual-redesign Requirement 1).
     */
    String description();

    /** {@code true} only for {@link TweakId#ANTI_DROP} (spec T1's whitelist-toggle hotkey). */
    default boolean hasSecondaryKeyBinding() {
        return false;
    }
}
