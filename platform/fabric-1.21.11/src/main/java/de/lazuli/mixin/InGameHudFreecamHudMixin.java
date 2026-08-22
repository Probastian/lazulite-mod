package de.lazuli.mixin;

import de.lazuli.tweaks.FreecamTicker;
import de.lazuli.tweaks.TweakEngineHandoff;
import de.lazuli.tweaks.TweakHooksImpl;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.entity.player.PlayerEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tweaks spec T14 (Freecam) Addendum 2 AD-7 -- HUD (hotbar/health/hunger/
 * armor/air) hidden by default while Freecam is active; add a toggle,
 * default to shown. Also AD-8's HUD half: a fixed reveal window when
 * {@code onHurt == HURT_INDICATOR}.
 *
 * <p><strong>Confirmed root cause/choke point via {@code javap -c} against
 * this module's own resolved merged Minecraft jar:</strong> {@code
 * InGameHud.getCameraPlayer()} (private, {@code this.client.getCameraEntity()
 * instanceof PlayerEntity ? (PlayerEntity) ... : null}) is a single shared
 * choke point with exactly three call sites in this class -- {@code
 * renderHotbar}, {@code renderStatusBars} (itself gating hearts/food/armor/
 * air bubbles), and {@code getRiddenEntity} (mount health) -- confirmed
 * identical in shape and call-site count to {@code fabric-26.1}'s {@code
 * Gui.getCameraPlayer()} and {@code fabric-26.2}'s {@code
 * Hud.getCameraPlayer()} (no 26.1-vs-26.2 divergence found here, unlike
 * several other adjacent HUD-family methods). The XP/contextual bar family
 * is a separate, unaffected call path (confirmed absent from this method's
 * call sites) -- out of scope per spec, untouched by this mixin.
 *
 * <p>Fix: while Freecam is active and either the user has not opted into
 * {@code hideHudWhileActive} or AD-8's hurt-reveal window is currently open,
 * spoof this one method's return value to the real local player -- mirrors
 * {@code WorldRendererFreecamShowBodyMixin}'s existing "there is only ever
 * one local player instance" spoof-the-return-value shape exactly, applied
 * to the single owning method rather than each of its three call sites.
 */
@Mixin(InGameHud.class)
abstract class InGameHudFreecamHudMixin {

    @Inject(method = "getCameraPlayer", at = @At("HEAD"), cancellable = true)
    private void lazuli$revealHudDuringFreecam(CallbackInfoReturnable<PlayerEntity> cir) {
        TweakHooksImpl hooks = TweakEngineHandoff.require();
        if (!hooks.isFreecamActive()) {
            return;
        }
        if (hooks.freecamHideHudWhileActive() && !FreecamTicker.isHurtRevealActive()) {
            return;
        }
        PlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null) {
            cir.setReturnValue(player);
        }
    }
}
