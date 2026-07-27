package de.lazuli.mixin;

import de.lazuli.MainMenuScreenFactoryHandoff;
import de.lazuli.api.mainmenu.MainMenuContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Main Menu ("Stonebound") spec FR1.2: redirects every vanilla "return to
 * title screen" call site to construct a fresh {@code MainMenuScreen}
 * instead of vanilla's own {@link TitleScreen}. Extended by
 * main-menu-pause-integration spec FR5 to also redirect vanilla's
 * {@link PauseScreen} (Esc while a world is running) to the same
 * {@code MainMenuScreen}, opened in {@link MainMenuContext#PAUSE} instead.
 *
 * <p><strong>Single choke point, confirmed via {@code javap} against this
 * module's own resolved (Mojang-mapped, unobfuscated) {@code minecraft-client.jar}
 * -- not enumerated per call site, per this plan's own allowance:</strong>
 * every vanilla path that ends up showing a fresh {@link TitleScreen}
 * ultimately funnels through {@code Gui.setScreen(Screen)} --
 * {@code Minecraft.setScreenAndShow(Screen)} itself simply delegates to
 * {@code this.gui.setScreen(screen)} (confirmed by bytecode read), and
 * {@code Minecraft.disconnectFromWorld(Component)} (the world-exit path) and
 * {@code Minecraft.abortResourcePackRecovery()} both construct
 * {@code new TitleScreen()} and hand it directly to
 * {@code Gui.setScreen(Screen)} too. The disconnect screen's own "Back to
 * Title Screen" button (confirmed via {@code javap} of
 * {@code DisconnectedScreen.class}) calls back through this exact same
 * {@code Minecraft.setScreenAndShow}/{@code Gui.setScreen} path with its
 * already-held {@code parent} field (itself a {@code TitleScreen} instance
 * constructed by whichever code opened the disconnect screen in the first
 * place) -- so intercepting here also covers that case without needing to
 * separately mixin into the button's own {@code onPress} lambda or every
 * {@code new TitleScreen()} call site across the codebase individually.
 *
 * <p><strong>Pause-trigger branch (main-menu-pause-integration FR5, confirmed
 * via {@code javap} of this module's own resolved jar):</strong>
 * {@code Gui.setPauseScreen(boolean, boolean)} -- vanilla's own Esc-opens-
 * pause-menu handler -- constructs {@code new PauseScreen(boolean)} and hands
 * it directly to this exact same {@code Gui.setScreen(Screen)} method, so no
 * second mixin/choke-point is needed; this is simply one more
 * {@code instanceof} arm in the same {@code @ModifyVariable} body.
 *
 * <p>Swaps the {@code screen} parameter itself (via {@code @ModifyVariable},
 * not {@code @Inject}+cancel) so the rest of {@code Gui.setScreen}'s own body
 * (input-type reset, {@code Screen.removed()} on the previous screen, the
 * teardown-in-progress guard, etc.) still runs unchanged against the
 * substituted screen -- only the *which screen* decision is altered, matching
 * this repo's minimal-footprint mixin discipline.
 *
 * <p><strong>Uncertainty flagged per this batch's own instructions:</strong>
 * this was derived from `javap -c` bytecode reading of the resolved
 * `26.2` jar only (no decompiled Java source was available/consulted), and
 * only the call sites `javap` actually surfaced (`Minecraft.disconnectFromWorld`,
 * `Minecraft.abortResourcePackRecovery`, `Minecraft.setScreenAndShow`,
 * `Gui.setPauseScreen`) were confirmed to route through `Gui.setScreen`. If a
 * future Minecraft version adds a "return to title"/"open pause menu" path
 * that does *not* go through `Gui.setScreen(Screen)` (e.g. a screen
 * constructed and rendered without ever being installed via that method), it
 * would not be caught by this mixin -- no such path was found in this pass,
 * but this is not a mathematically exhaustive proof over 100% of vanilla's
 * source.
 *
 * <p><strong>Bugfix follow-up (main-menu-pause-integration, quit-to-title
 * regression): the {@code screen == null} auto-recovery branch, confirmed via
 * {@code javap} of {@code Gui.setScreen(Screen)} itself.</strong> When
 * {@code Gui.setScreen} is called with a {@code null} argument while no level
 * is loaded, vanilla does NOT leave the screen unset -- it constructs a raw
 * {@code new TitleScreen()} <em>internally</em>, entirely inside the method
 * body, and assigns it straight to the {@code screen} field. Since
 * {@code @ModifyVariable} only observes the argument as it existed at
 * {@code @At("HEAD")}, a {@code null} argument never matches
 * {@code instanceof TitleScreen} and this internal reassignment is invisible
 * to this mixin -- any caller (vanilla or another mod) that returns to the
 * title by passing {@code null} bypasses this redirect entirely and shows a
 * bare vanilla {@code TitleScreen}, not {@code MainMenuScreen}. The extra
 * {@code screen == null} arm below replicates vanilla's own condition for
 * that branch ({@code Minecraft.getInstance().level == null}) so this
 * redirect fires for that path too.
 */
@Mixin(Gui.class)
public abstract class GuiTitleScreenRedirectMixin {

    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen lazuli$redirectVanillaScreensToMainMenu(Screen screen) {
        if (screen instanceof TitleScreen) {
            return MainMenuScreenFactoryHandoff.require().apply(MainMenuContext.MAIN_MENU);
        } else if (screen instanceof PauseScreen) {
            return MainMenuScreenFactoryHandoff.require().apply(MainMenuContext.PAUSE);
        } else if (screen == null && Minecraft.getInstance().level == null) {
            return MainMenuScreenFactoryHandoff.require().apply(MainMenuContext.MAIN_MENU);
        }
        return screen;
    }
}
