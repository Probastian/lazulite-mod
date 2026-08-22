package de.lazuli.mixin;

import de.lazuli.tweaks.FreecamTicker;
import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tweaks spec T14 (Freecam) Addendum 2 AD-8 -- new "On Hurt" 3-option
 * dropdown, detection layer.
 *
 * <p><strong>Confirmed damage-detection hook via {@code javap -c} against
 * this module's own resolved merged Minecraft jar:</strong> {@code
 * LivingEntity.animateDamage(float yaw)} (public -- Yarn names this method
 * {@code animateDamage}, not {@code animateHurt}; see {@code
 * .claude/context/minecraft.md} for the confirmed cross-mapping naming
 * divergence) is genuinely a distinct mechanism from the general per-entity
 * damage-flash pipeline ({@code Entity.onDamaged(DamageSource)}, driven by
 * {@code EntityDamageS2CPacket}, which every tracked entity including remote
 * players receives) -- it sets the same {@code hurtTime}/{@code
 * hurtDuration} fields but is driven by a separate packet, {@code
 * DamageTiltS2CPacket}, confirmed constructed and sent ONLY from {@code
 * ServerPlayerEntity}'s own damage-indication method directly to that
 * player's own connection (never broadcast to trackers), itself called from
 * {@code LivingEntity}'s default-knockback handling on every non-blocked
 * hurt for any entity that is a {@code ServerPlayerEntity} -- i.e. this is
 * vanilla's own existing "hurt direction indicator" mechanism (the option
 * name {@code HURT_INDICATOR} is not a coincidence) and is confirmed, by
 * this exact server-side call chain (independently verified on this
 * platform's own jar, not just ported from the 26.x finding), to genuinely
 * fire for the local player's own entity instance specifically (not just
 * remote players).
 *
 * <p>Fix: a thin, non-cancelling detection layer scoped to the local
 * player's own entity instance while Freecam is active -- mirrors {@code
 * PlayerEntityFreecamSuppressSneakMixin}'s existing "local-player-scoped,
 * Freecam-gated" guard shape. On firing, forwards to {@link
 * FreecamTicker#onLocalPlayerHurt()}, which owns all of AD-8's cross-tick
 * bookkeeping (hurt-reveal timer, disable-Freecam safety-net latch) -- this
 * mixin never itself mutates Freecam/HUD state and never cancels vanilla's
 * own hurt-flash animation.
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityFreecamOnHurtMixin {

    @Inject(method = "animateDamage", at = @At("HEAD"))
    private void lazuli$detectLocalPlayerHurtDuringFreecam(float yaw, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof ClientPlayerEntity && self == MinecraftClient.getInstance().player
                && TweakEngineHandoff.require().isFreecamActive()) {
            FreecamTicker.onLocalPlayerHurt();
        }
    }
}
