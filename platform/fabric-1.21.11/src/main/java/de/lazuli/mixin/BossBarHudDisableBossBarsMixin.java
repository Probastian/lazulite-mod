package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.entity.boss.BossBar;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tweaks spec T12 (Disable Boss Bars): skips rendering a boss bar entry
 * whose name matches the tweak's whitelist/blacklist/all mode.
 *
 * <p>Targets the private per-entry {@code BossBarHud.renderBossBar(
 * DrawContext, int, int, BossBar)} overload (confirmed via {@code javap}),
 * cancelling before any draw call for excluded entries.
 */
@Mixin(BossBarHud.class)
abstract class BossBarHudDisableBossBarsMixin {

    @Inject(method = "renderBossBar(Lnet/minecraft/client/gui/DrawContext;IILnet/minecraft/entity/boss/BossBar;)V",
            at = @At("HEAD"), cancellable = true)
    private void lazuli$maybeHide(DrawContext context, int x, int y, BossBar bossBar, CallbackInfo ci) {
        String name = bossBar.getName().getString();
        if (TweakEngineHandoff.require().shouldHideBossBar(name)) {
            ci.cancel();
        }
    }
}
