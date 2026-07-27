package de.lazuli.tweaks;

import de.lazuli.features.tweaks.services.TweakRegistry;

/** The two objects the Tweaks tab UI needs, published together via {@code TweakRegistryHandoff}. */
public record TweaksBundle(TweakRegistry registry, TweaksKeyBindings keyBindings) {
}
