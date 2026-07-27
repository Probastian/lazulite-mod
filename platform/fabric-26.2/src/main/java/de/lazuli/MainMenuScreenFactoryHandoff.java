package de.lazuli;

import de.lazuli.api.mainmenu.MainMenuContext;

import net.minecraft.client.gui.screens.Screen;

import java.util.function.Function;

/**
 * A narrow, composition-root-scoped hand-off publishing a factory that
 * builds a fresh {@code MainMenuScreen} instance for a given
 * {@link MainMenuContext} -- consumed by
 * {@link de.lazuli.mixin.GuiTitleScreenRedirectMixin} (spec FR1.2, extended
 * by main-menu-pause-integration FR5) so the mixin, which is merged directly
 * into vanilla's own {@code Gui} class and therefore has no constructor call
 * site to inject a dependency through, can still obtain the same
 * already-constructed background renderer/state dependencies
 * {@link MainMenuClientInitializer} builds once at startup, exactly the same
 * "static holder bridges a mixin into feature-composed services" shape
 * {@code WorldSyncToggleHookHolder}/{@code SteamworksServiceHandoff} already
 * establish elsewhere in this repo.
 *
 * <p>Each {@link Function#apply(Object)} call must construct a brand-new
 * {@code MainMenuScreen} (never return a cached instance) so FR1.3's
 * "state resets to defaults on every fresh construction" bar holds for the
 * disconnect/world-exit path exactly as it does for the initial boot path --
 * and, since main-menu-pause-integration, for the pause-trigger path too.
 */
public final class MainMenuScreenFactoryHandoff {

    private static volatile Function<MainMenuContext, Screen> factory;

    private MainMenuScreenFactoryHandoff() {
    }

    public static void publish(Function<MainMenuContext, Screen> screenFactory) {
        factory = screenFactory;
    }

    public static Function<MainMenuContext, Screen> require() {
        Function<MainMenuContext, Screen> published = factory;
        if (published == null) {
            throw new IllegalStateException(
                    "MainMenuScreenFactoryHandoff.require() called before MainMenuClientInitializer "
                            + "published a MainMenuScreen factory -- check this module's fabric.mod.json \"client\" entrypoint order.");
        }
        return published;
    }
}
