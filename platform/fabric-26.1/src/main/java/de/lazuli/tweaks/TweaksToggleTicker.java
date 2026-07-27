package de.lazuli.tweaks;

import de.lazuli.api.tweaks.TweakId;
import de.lazuli.features.tweaks.services.TweakRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;

/**
 * Per-tick edge-trigger toggle for every primary hotkey <em>except</em>
 * {@link TweakId#ZOOM} (which has its own hold/toggle state machine, see
 * {@link ZoomTicker}): on {@code KeyMapping.consumeClick()} flips the
 * tweak's enabled state via {@link TweakRegistry#setEnabled}, mirroring the
 * checkbox-click behavior in {@code TweaksPanel} (spec F1: "pressing a
 * tweak's bound hotkey toggles it exactly like clicking its checkbox").
 *
 * <p>Previously missing entirely: {@code TweaksKeyBindings} registered all
 * 12 primary {@code KeyMapping}s, but only Zoom had a tick-poller wired into
 * {@code TweaksClientInitializer}, so every other tweak's hotkey was
 * registered (and thus bindable/visible in Controls) but never actually
 * polled, meaning {@code consumeClick()} was never called and
 * {@link TweakRegistry#setEnabled} was never invoked.
 */
public final class TweaksToggleTicker {

    private TweaksToggleTicker() {
    }

    public static void register(TweaksKeyBindings keyBindings, TweakRegistry registry) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            for (TweakId id : TweakId.values()) {
                if (id == TweakId.ZOOM) {
                    continue;
                }
                KeyMapping mapping = keyBindings.keyBindingOf(id);
                if (mapping != null && mapping.consumeClick()) {
                    registry.setEnabled(id, !registry.stateOf(id).enabled());
                }
            }
        });
    }
}
