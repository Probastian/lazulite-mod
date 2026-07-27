package de.lazuli.mixin;

import de.lazuli.MainMenuScreenFactoryHandoff;
import de.lazuli.api.mainmenu.MainMenuContext;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Main Menu ("Stonebound") spec FR1.2: redirects every vanilla "return to
 * title screen" call site to construct a fresh {@code MainMenuScreen}
 * instead of vanilla's own {@link TitleScreen} -- {@code fabric-1.21.11}
 * (Yarn-mapped, obfuscated) port of {@code fabric-26.1}/{@code fabric-26.2}'s
 * {@code GuiTitleScreenRedirectMixin}. Extended by main-menu-pause-integration
 * spec FR5 to also redirect vanilla's {@link GameMenuScreen} (Yarn's name for
 * the pause menu -- confirmed via this module's own Yarn mappings, never
 * named {@code PauseScreen} on this mapping set) to the same
 * {@code MainMenuScreen}, opened in {@link MainMenuContext#PAUSE} instead.
 *
 * <p><strong>Single choke point, confirmed via {@code javap -p} against this
 * module's own resolved (Yarn-mapped) merged Minecraft jar:</strong> unlike
 * 26.x (where screen-setting was refactored onto a separate {@code Gui}
 * class), on this version {@code MinecraftClient.setScreen(Screen)} itself
 * remains the single choke point every "return to title screen" path funnels
 * through (the disconnect path's {@code disconnect(Text)}/
 * {@code disconnectWithSavingScreen()}/{@code disconnectWithProgressScreen()}
 * overloads, and world-exit, all ultimately hand a fresh {@code TitleScreen}
 * to this same method).
 *
 * <p><strong>Pause-trigger branch (main-menu-pause-integration FR5, confirmed
 * via {@code javap} of this module's own resolved jar):</strong> vanilla's own
 * Esc-opens-pause-menu handler constructs {@code new GameMenuScreen(boolean)}
 * and hands it directly to this exact same {@code MinecraftClient.setScreen(Screen)}
 * method, so no second mixin/choke-point is needed; this is simply one more
 * {@code instanceof} arm in the same {@code @ModifyVariable} body.
 *
 * <p>Redirecting here, mirroring the 26.x mixin's own
 * {@code @ModifyVariable}-not-{@code @Inject} discipline so the rest of
 * {@code setScreen}'s own body (input-type reset, previous screen's
 * {@code removed()}, etc.) still runs unchanged against the substituted
 * screen.
 *
 * <p><strong>Bugfix follow-up (main-menu-pause-integration, quit-to-title
 * regression): the {@code screen == null} auto-recovery branch, confirmed via
 * {@code javap} of this module's own resolved (Yarn-mapped) jar's
 * {@code MinecraftClient.setScreen(Screen)}.</strong> Identical shape to the
 * 26.x {@code Gui.setScreen} case: when called with a {@code null} argument
 * while no world is loaded, vanilla does NOT leave the screen unset -- it
 * constructs a raw {@code new TitleScreen()} <em>internally</em>, entirely
 * inside the method body, invisible to {@code @ModifyVariable} since that
 * only observes the argument as it existed at {@code @At("HEAD")}. Any caller
 * (vanilla or another mod) that returns to the title by passing {@code null}
 * bypassed this redirect entirely and showed a bare vanilla
 * {@code TitleScreen}, not {@code MainMenuScreen}. The extra
 * {@code screen == null} arm below replicates vanilla's own condition for
 * that branch ({@code MinecraftClient.getInstance().world == null}) so this
 * redirect fires for that path too.
 */
@Mixin(MinecraftClient.class)
public abstract class ClientTitleScreenRedirectMixin {

    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen lazuli$redirectVanillaScreensToMainMenu(Screen screen) {
        if (screen instanceof TitleScreen) {
            return MainMenuScreenFactoryHandoff.require().apply(MainMenuContext.MAIN_MENU);
        } else if (screen instanceof GameMenuScreen) {
            return MainMenuScreenFactoryHandoff.require().apply(MainMenuContext.PAUSE);
        } else if (screen == null && MinecraftClient.getInstance().world == null) {
            return MainMenuScreenFactoryHandoff.require().apply(MainMenuContext.MAIN_MENU);
        }
        return screen;
    }
}
