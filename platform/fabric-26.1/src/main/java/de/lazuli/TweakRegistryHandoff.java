package de.lazuli;

import de.lazuli.tweaks.TweaksBundle;

/**
 * Publishes the single {@link TweaksBundle} {@link TweaksClientInitializer}
 * constructs, so {@link MainMenuClientInitializer} can obtain the same
 * {@code TweakRegistry}/{@code TweaksKeyBindings} instances rather than
 * constructing competing ones -- same shape as {@link FriendsSidebarFacadeHandoff}.
 *
 * <p>Correctness depends only on {@code TweaksClientInitializer} appearing
 * before {@code MainMenuClientInitializer} in this module's
 * {@code fabric.mod.json} {@code "client"} entrypoint array.
 */
public final class TweakRegistryHandoff {

    private static volatile TweaksBundle instance;

    private TweakRegistryHandoff() {
    }

    public static void publish(TweaksBundle bundle) {
        instance = bundle;
    }

    public static TweaksBundle require() {
        TweaksBundle published = instance;
        if (published == null) {
            throw new IllegalStateException(
                    "TweakRegistryHandoff.require() called before TweaksClientInitializer published a "
                            + "TweaksBundle -- check this module's fabric.mod.json \"client\" entrypoint order.");
        }
        return published;
    }
}
