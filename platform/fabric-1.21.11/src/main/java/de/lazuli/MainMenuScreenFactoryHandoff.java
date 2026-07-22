package de.lazuli;

import net.minecraft.client.gui.screen.Screen;

import java.util.function.Supplier;

/**
 * A narrow, composition-root-scoped hand-off publishing a factory that
 * builds a fresh {@code MainMenuScreen} instance -- consumed by
 * {@link de.lazuli.mixin.ClientTitleScreenRedirectMixin} (spec FR1.2) so the
 * mixin, which is merged directly into vanilla's own {@code MinecraftClient}
 * class and therefore has no constructor call site to inject a dependency
 * through, can still obtain the same already-constructed background
 * renderer/state dependencies {@link MainMenuClientInitializer} builds once
 * at startup, exactly the same "static holder bridges a mixin into
 * feature-composed services" shape {@code WorldSyncToggleHookHolder}/
 * {@code SteamworksServiceHandoff} already establish elsewhere in this repo.
 * {@code fabric-1.21.11} (Yarn-mapped) port of the {@code fabric-26.1}/
 * {@code fabric-26.2} class of the same name.
 *
 * <p>Each {@link Supplier#get()} call must construct a brand-new
 * {@code MainMenuScreen} (never return a cached instance) so FR1.3's
 * "state resets to defaults on every fresh construction" bar holds for the
 * disconnect/world-exit path exactly as it does for the initial boot path.
 */
public final class MainMenuScreenFactoryHandoff {

    private static volatile Supplier<Screen> factory;

    private MainMenuScreenFactoryHandoff() {
    }

    public static void publish(Supplier<Screen> screenFactory) {
        factory = screenFactory;
    }

    public static Supplier<Screen> require() {
        Supplier<Screen> published = factory;
        if (published == null) {
            throw new IllegalStateException(
                    "MainMenuScreenFactoryHandoff.require() called before MainMenuClientInitializer "
                            + "published a MainMenuScreen factory -- check this module's fabric.mod.json \"client\" entrypoint order.");
        }
        return published;
    }
}
