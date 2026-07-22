package de.lazuli.mixin;

import de.lazuli.MainMenuScreenFactoryHandoff;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Main Menu ("Stonebound") spec FR1.2: redirects every vanilla "return to
 * title screen" call site to construct a fresh {@code MainMenuScreen}
 * instead of vanilla's own {@link TitleScreen}.
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
 * `Minecraft.abortResourcePackRecovery`, `Minecraft.setScreenAndShow`) were
 * confirmed to route through `Gui.setScreen`. If a future Minecraft version
 * adds a "return to title" path that does *not* go through
 * `Gui.setScreen(Screen)` (e.g. a screen constructed and rendered without
 * ever being installed via that method), it would not be caught by this
 * mixin -- no such path was found in this pass, but this is not a
 * mathematically exhaustive proof over 100% of vanilla's source.
 */
@Mixin(Gui.class)
public abstract class GuiTitleScreenRedirectMixin {

    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen lazuli$redirectTitleScreenToMainMenu(Screen screen) {
        if (screen instanceof TitleScreen) {
            return MainMenuScreenFactoryHandoff.require().get();
        }
        return screen;
    }
}
