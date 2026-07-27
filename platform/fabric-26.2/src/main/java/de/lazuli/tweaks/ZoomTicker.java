package de.lazuli.tweaks;

import de.lazuli.api.tweaks.TweakId;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;

/**
 * Per-tick hold/toggle state machine for T11 Zoom (spec Architecture: "T11
 * Zoom additionally reads {@code KeyBinding.isDown()} each frame ... rather
 * than only edge-triggering on {@code wasPressed()}"), registered via Fabric
 * API's {@code ClientTickEvents.END_CLIENT_TICK} -- no mixin needed for the
 * key-state polling itself. Note this only tracks hold/toggle state; nothing
 * yet applies it to the client's actual FOV (see {@link TweakHooksImpl}'s
 * Javadoc), so Zoom has no visible in-game effect yet.
 */
public final class ZoomTicker {

    private ZoomTicker() {
    }

    public static void register(TweaksKeyBindings keyBindings, TweakHooksImpl hooks) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            KeyMapping mapping = keyBindings.keyBindingOf(TweakId.ZOOM);
            boolean holdToZoom = true;
            Object rawHoldToZoom = hooks.holdToZoomConfigurable();
            if (rawHoldToZoom instanceof Boolean b) {
                holdToZoom = b;
            }
            if (holdToZoom) {
                hooks.setZoomActive(mapping.isDown());
            } else if (mapping.consumeClick()) {
                hooks.setZoomActive(!hooks.isZoomActive());
            }
        });
    }
}
