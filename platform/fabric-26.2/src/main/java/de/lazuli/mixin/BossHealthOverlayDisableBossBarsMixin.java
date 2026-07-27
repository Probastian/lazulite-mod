package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.world.BossEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tweaks spec T12 (Disable Boss Bars): skips extracting a boss bar entry's
 * render state when its name matches the tweak's whitelist/blacklist/all
 * mode.
 *
 * <p><strong>Confirmed via {@code javap}, corrects the spec/plan's guessed
 * {@code render(...)} method:</strong> {@code BossHealthOverlay} uses the
 * render-state-extraction model -- the private per-entry {@code
 * extractBar(GuiGraphicsExtractor, int, int, BossEvent)} overload is the
 * real target, confirmed identical on 26.1 and 26.2 (the {@code Gui}/{@code
 * Hud} split found elsewhere only affects the owning top-level class, not
 * this one).
 */
@Mixin(BossHealthOverlay.class)
abstract class BossHealthOverlayDisableBossBarsMixin {

    @Inject(method = "extractBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/world/BossEvent;)V",
            at = @At("HEAD"), cancellable = true)
    private void lazuli$maybeHide(GuiGraphicsExtractor extractor, int x, int y, BossEvent bossEvent, CallbackInfo ci) {
        String name = bossEvent.getName().getString();
        if (TweakEngineHandoff.require().shouldHideBossBar(name)) {
            ci.cancel();
        }
    }
}
