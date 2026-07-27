package de.lazuli.tweaks;

import de.lazuli.api.tweaks.TweakId;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.option.KeyBinding;

/**
 * Per-tick hold/toggle state machine for T11 Zoom (spec Architecture).
 * Yarn-mapped port of {@code fabric-26.1}/{@code fabric-26.2}'s class of the
 * same name -- {@code KeyBinding.wasPressed()} (edge-trigger) is this
 * version's equivalent of Mojmap's {@code KeyMapping.consumeClick()} (see
 * {@code .claude/context/minecraft.md}'s Known Cross-Version API Differences
 * table).
 */
public final class ZoomTicker {

    private ZoomTicker() {
    }

    public static void register(TweaksKeyBindings keyBindings, TweakHooksImpl hooks) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            KeyBinding binding = keyBindings.keyBindingOf(TweakId.ZOOM);
            boolean holdToZoom = true;
            Object rawHoldToZoom = hooks.holdToZoomConfigurable();
            if (rawHoldToZoom instanceof Boolean b) {
                holdToZoom = b;
            }
            if (holdToZoom) {
                hooks.setZoomActive(binding.isPressed());
            } else if (binding.wasPressed()) {
                hooks.setZoomActive(!hooks.isZoomActive());
            }
        });
    }
}
