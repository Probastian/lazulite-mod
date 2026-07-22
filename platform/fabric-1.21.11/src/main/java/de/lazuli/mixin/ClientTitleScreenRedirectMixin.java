package de.lazuli.mixin;

import de.lazuli.MainMenuScreenFactoryHandoff;

import net.minecraft.client.MinecraftClient;
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
 * {@code GuiTitleScreenRedirectMixin}.
 *
 * <p><strong>Single choke point, confirmed via {@code javap -p} against this
 * module's own resolved (Yarn-mapped) merged Minecraft jar:</strong> unlike
 * 26.x (where screen-setting was refactored onto a separate {@code Gui}
 * class), on this version {@code MinecraftClient.setScreen(Screen)} itself
 * remains the single choke point every "return to title screen" path funnels
 * through (the disconnect path's {@code disconnect(Text)}/
 * {@code disconnectWithSavingScreen()}/{@code disconnectWithProgressScreen()}
 * overloads, and world-exit, all ultimately hand a fresh {@code TitleScreen}
 * to this same method). Redirecting here, mirroring the 26.x mixin's own
 * {@code @ModifyVariable}-not-{@code @Inject} discipline so the rest of
 * {@code setScreen}'s own body (input-type reset, previous screen's
 * {@code removed()}, etc.) still runs unchanged against the substituted
 * screen.
 */
@Mixin(MinecraftClient.class)
public abstract class ClientTitleScreenRedirectMixin {

    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen lazuli$redirectTitleScreenToMainMenu(Screen screen) {
        if (screen instanceof TitleScreen) {
            return MainMenuScreenFactoryHandoff.require().get();
        }
        return screen;
    }
}
