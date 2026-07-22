package de.lazuli;

import de.lazuli.services.ui.ToastService;

/**
 * Narrow, composition-root-scoped hand-off for the platform's single
 * {@link ToastService}, identical shape to {@link SteamworksServiceHandoff}/
 * {@code WorldHostingBridgeHandoff}. Published unconditionally (no Steam/
 * config gate -- a toast service has neither) by
 * {@code SteamworksClientInitializer}, before every other feature's own
 * composition root runs; {@code require()}d by any later consumer (e.g.
 * {@code FriendsSidebarClientInitializer}).
 */
public final class ToastServiceHandoff {

    private static volatile ToastService toastService;

    private ToastServiceHandoff() {
    }

    /** Publishes the platform's single {@link ToastService}; called once by {@code SteamworksClientInitializer}. */
    public static void publish(ToastService service) {
        toastService = service;
    }

    /** @return the published {@link ToastService}. */
    public static ToastService require() {
        ToastService value = toastService;
        if (value == null) {
            throw new IllegalStateException(
                    "ToastServiceHandoff.require() called before SteamworksClientInitializer published the toast "
                            + "service -- check this module's fabric.mod.json \"client\" entrypoint order "
                            + "(SteamworksClientInitializer must run first).");
        }
        return value;
    }
}
