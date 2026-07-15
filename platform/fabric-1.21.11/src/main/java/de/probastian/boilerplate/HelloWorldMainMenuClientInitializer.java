package de.probastian.boilerplate;

import de.probastian.boilerplate.features.helloworldmainmenu.config.HelloWorldMainMenuConfigIO;
import de.probastian.boilerplate.features.helloworldmainmenu.services.HelloWorldMainMenuService;
import de.probastian.boilerplate.mainmenu.FabricMainMenuHook;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * Client-only composition root for the "Hello World Main Menu" feature on
 * this platform module.
 *
 * <p>Wires together this platform's {@link FabricMainMenuHook} (the Version
 * Adapter), the feature's {@code HelloWorldMainMenuConfigIO}, and the
 * feature's {@code HelloWorldMainMenuService}, then applies the config to
 * the title screen once at client startup. Registered as a {@code "client"}
 * entrypoint in this module's {@code fabric.mod.json}, alongside the
 * existing server-safe {@code "main"} entrypoint ({@link TemplateMod}).
 *
 * <p>Per this feature's ADR
 * ({@code docs/adr/0001-platform-composition-root-may-depend-on-feature-classes.md}),
 * a platform module's composition root is permitted to reference concrete
 * Feature classes purely for bootstrap wiring, even though ordinary
 * business-logic code must not depend across the Feature/Platform boundary
 * in the other direction.
 *
 * <p>Usage example (this is how Fabric Loader invokes it; not normally
 * called directly):
 * <pre>{@code
 * new HelloWorldMainMenuClientInitializer().onInitializeClient();
 * }</pre>
 */
public final class HelloWorldMainMenuClientInitializer implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve("hello-world-main-menu.json");

        FabricMainMenuHook hook = new FabricMainMenuHook();
        HelloWorldMainMenuConfigIO configIO = new HelloWorldMainMenuConfigIO();
        HelloWorldMainMenuService service =
                new HelloWorldMainMenuService(hook, configIO, configPath, TemplateMod.LOGGER::warn);

        service.applyToMainMenu();
    }
}
