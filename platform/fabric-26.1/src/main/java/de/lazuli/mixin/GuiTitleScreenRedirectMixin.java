package de.lazuli.mixin;

import de.lazuli.MainMenuScreenFactoryHandoff;
import de.lazuli.api.mainmenu.MainMenuContext;

import net.minecraft.client.Minecraft;
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
 * <p><strong>26.1-specific choke point, re-verified via {@code javap}/{@code
 * javap -c} against this module's own resolved (Mojang-mapped, unobfuscated)
 * {@code minecraft-client.jar} for the 26.1 mappings -- do NOT assume this
 * matches the 26.2 module's mixin target, it does not:</strong> on 26.1's
 * mappings, {@code net.minecraft.client.gui.Gui} has no screen-related
 * methods at all ({@code javap -p} on {@code Gui.class} only surfaces
 * debug-overlay methods); {@code setScreen(Screen)} and {@code
 * setScreenAndShow(Screen)} both live directly on {@code Minecraft} instead.
 * {@code javap -c} confirms {@code Minecraft.setScreenAndShow(Screen)}
 * simply delegates to {@code this.setScreen(screen)} (a same-class
 * {@code invokevirtual}), so this mixin only needs to target {@code
 * Minecraft.setScreen(Screen)} itself, matching the single-choke-point
 * pattern the 26.2 module uses (just against a different target class).
 *
 * <p><strong>Pause-trigger branch (main-menu-pause-integration FR5,
 * re-confirmed via {@code javap -c} of this module's own resolved 26.1
 * jar):</strong> {@code Minecraft.pauseGame(boolean)} -- vanilla's own
 * Esc-opens-pause-menu handler -- constructs {@code new PauseScreen(boolean)}
 * and hands it directly to {@code this.setScreen(Screen)} (bytecode:
 * {@code invokespecial PauseScreen.<init>} immediately followed by
 * {@code invokevirtual setScreen}), so no second mixin/choke-point is needed
 * here either; this is simply one more {@code instanceof} arm in the same
 * {@code @ModifyVariable} body.
 *
 * <p>Swaps the {@code screen} parameter itself (via {@code @ModifyVariable},
 * not {@code @Inject}+cancel) so the rest of {@code Minecraft.setScreen}'s
 * own body (input-type reset, {@code Screen.removed()} on the previous
 * screen, the teardown-in-progress guard, etc.) still runs unchanged against
 * the substituted screen -- only the *which screen* decision is altered,
 * matching this repo's minimal-footprint mixin discipline.
 *
 * <p><strong>Uncertainty flagged per this repo's own instructions:</strong>
 * this was derived from {@code javap -c} bytecode reading of the resolved
 * 26.1 jar only (no decompiled Java source was available/consulted), and
 * only the call sites {@code javap} actually surfaced ({@code
 * Minecraft.setScreenAndShow}, {@code Minecraft.pauseGame}, and {@code
 * Minecraft.setScreen}'s own internal null-recovery branch, see below) were
 * confirmed to route through {@code Minecraft.setScreen}. If a future
 * Minecraft version adds a "return to title"/"open pause menu" path that
 * does *not* go through {@code Minecraft.setScreen(Screen)}, it would not be
 * caught by this mixin -- no such path was found in this pass, but this is
 * not a mathematically exhaustive proof over 100% of vanilla's source.
 *
 * <p><strong>Null-screen auto-recovery branch, confirmed via {@code javap -c}
 * of {@code Minecraft.setScreen(Screen)} itself on 26.1's own jar.</strong>
 * When {@code Minecraft.setScreen} is called with a {@code null} argument
 * while no level is loaded ({@code this.level == null}), vanilla does NOT
 * leave the screen unset -- it constructs a raw {@code new TitleScreen()}
 * <em>internally</em>, entirely inside the method body, and assigns it to
 * the local variable that ultimately gets stored in the {@code screen}
 * field. Since {@code @ModifyVariable} only observes the argument as it
 * existed at {@code @At("HEAD")}, a {@code null} argument never matches
 * {@code instanceof TitleScreen} and this internal reassignment is invisible
 * to this mixin -- any caller (vanilla or another mod) that returns to the
 * title by passing {@code null} bypasses this redirect entirely and shows a
 * bare vanilla {@code TitleScreen}, not {@code MainMenuScreen}. The extra
 * {@code screen == null} arm below replicates vanilla's own condition for
 * that branch ({@code Minecraft.getInstance().level == null}) so this
 * redirect fires for that path too.
 */
@Mixin(Minecraft.class)
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
