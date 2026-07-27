package de.lazuli.tweaks;

import de.lazuli.api.tweaks.TweakId;
import de.lazuli.features.tweaks.services.TweakRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.option.KeyBinding;

/**
 * Per-tick edge-trigger toggle for every primary hotkey <em>except</em>
 * {@link TweakId#ZOOM} (which has its own hold/toggle state machine, see
 * {@link ZoomTicker}): on {@code KeyBinding.wasPressed()} flips the tweak's
 * enabled state via {@link TweakRegistry#setEnabled}, mirroring the
 * checkbox-click behavior in {@code TweaksPanel} (spec F1: "pressing a
 * tweak's bound hotkey toggles it exactly like clicking its checkbox").
 *
 * <p>Previously missing entirely: {@code TweaksKeyBindings} registered all
 * 12 primary {@code KeyBinding}s, but only Zoom had a tick-poller wired into
 * {@code TweaksClientInitializer}, so every other tweak's hotkey was
 * registered (and thus bindable/visible in Controls) but never actually
 * polled, meaning {@code wasPressed()} was never called and
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
                KeyBinding binding = keyBindings.keyBindingOf(id);
                if (binding != null && binding.wasPressed()) {
                    registry.setEnabled(id, !registry.stateOf(id).enabled());
                }
            }
        });
    }
}
