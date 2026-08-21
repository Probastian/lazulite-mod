package de.lazuli;

import de.lazuli.features.tweaks.config.TweaksConfigIO;
import de.lazuli.features.tweaks.services.TweakRegistry;
import de.lazuli.tweaks.TweakHooksImpl;
import de.lazuli.tweaks.TweaksBundle;
import de.lazuli.tweaks.TweaksKeyBindings;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client-only composition root for the Tweaks feature (spec F1/F5):
 * registers all 13 vanilla {@code KeyMapping}s (12 primary + Anti-Drop's
 * secondary) at {@code onInitializeClient()} time (Risk #4: must happen
 * before vanilla's own {@code options.txt} load, i.e. during real mod init,
 * not lazily), loads {@code tweaks.json}, constructs {@link TweakRegistry}
 * with a write-through save callback (mirrors
 * {@code MainMenuClientInitializer}'s wardrobe-save-callback shape), and
 * publishes both via {@link TweakRegistryHandoff} for
 * {@code MainMenuClientInitializer}'s Tweaks tab to consume.
 *
 * <p>Registered <strong>before</strong> {@code MainMenuClientInitializer} in
 * this module's {@code fabric.mod.json} {@code "client"} entrypoint array.
 */
public final class TweaksClientInitializer implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        TweaksKeyBindings keyBindings = new TweaksKeyBindings();

        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path tweaksConfigPath = configDir.resolve("tweaks.json");
        TweaksConfigIO configIO = new TweaksConfigIO();
        TweaksConfigIO.ParseResult result = configIO.load(tweaksConfigPath);
        if (result.warning() != null) {
            LazuliMod.LOGGER.warn(result.warning());
        }

        TweakRegistry registry = new TweakRegistry(result.config(), updated -> {
            try {
                Files.writeString(tweaksConfigPath, configIO.serialize(updated));
            } catch (java.io.IOException e) {
                LazuliMod.LOGGER.warn("Failed to persist tweaks.json: " + e);
            }
        }, LazuliMod.LOGGER::info);

        TweakHooksImpl hooks = new TweakHooksImpl(registry);
        de.lazuli.tweaks.TweakEngineHandoff.publish(hooks);
        de.lazuli.tweaks.ZoomTicker.register(keyBindings, hooks);
        de.lazuli.tweaks.TweaksToggleTicker.register(keyBindings, registry);
        de.lazuli.tweaks.FreecamTicker.register(keyBindings, hooks, registry);

        TweakRegistryHandoff.publish(new TweaksBundle(registry, keyBindings));
    }
}
