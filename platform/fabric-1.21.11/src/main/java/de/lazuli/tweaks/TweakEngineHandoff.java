package de.lazuli.tweaks;

/**
 * Publishes the single {@link TweakHooksImpl} instance so future mixins
 * added in a follow-up implementation batch (see {@link TweakHooksImpl}'s
 * own Javadoc, Risk #1's deferred per-tweak {@code javap} pass) can consume
 * it without threading it through additional constructor parameters.
 */
public final class TweakEngineHandoff {

    private static volatile TweakHooksImpl instance;

    private TweakEngineHandoff() {
    }

    public static void publish(TweakHooksImpl hooks) {
        instance = hooks;
    }

    public static TweakHooksImpl require() {
        TweakHooksImpl published = instance;
        if (published == null) {
            throw new IllegalStateException(
                    "TweakEngineHandoff.require() called before TweaksClientInitializer published a TweakHooksImpl.");
        }
        return published;
    }
}
