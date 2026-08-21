package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.network.ClientPlayerEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Tweaks spec T14 (Freecam) -- real bug fix, not part of the original
 * spec's own analysis (see {@code .claude/context/minecraft.md} row 112 for
 * the closely-related, already-documented movement-key-routing finding this
 * one extends).
 *
 * <p><strong>Root cause, confirmed via {@code javap -c} against this
 * module's own resolved merged Minecraft jar (Yarn port of {@code
 * fabric-26.2}'s mixin of the same rationale, {@code
 * LocalPlayerFreecamKeepPositionSyncMixin}):</strong> {@code
 * ClientPlayerEntity.sendMovementPackets()} (private, called unconditionally
 * every client tick whenever the player is not a passenger -- confirmed via
 * bytecode trace of the caller) gates its ENTIRE body -- outgoing movement/
 * rotation packets plus the {@code lastXClient}/etc. delta-bookkeeping used
 * to compute next tick's delta -- behind {@code isCamera()} (the same
 * {@code client.getCameraEntity() == this} gate row 112 already documented
 * for {@code tickMovementInput()}, confirmed via {@code javap -c}: an
 * {@code ifeq} jumps straight past the entire method body to its final
 * {@code return} when the check is false).
 *
 * <p>Once Freecam's {@code FreecamTicker} points {@code
 * MinecraftClient.setCameraEntity} away from the real player, this gate
 * silently stops the real player's own position/rotation/ground-status from
 * ever being networked to the server while Freecam is active, even though
 * the spec requires the player to keep moving locally exactly as normal.
 * When Freecam deactivates and the gate reopens, {@code
 * sendMovementPackets()} computes its delta against the stale last-sent
 * position, producing one large single-packet "teleport" that a
 * server-side anti-cheat plugin can reject/correct -- observed in-game as a
 * screen-flash/rubber-band and an anti-cheat log line rejecting an
 * abnormal position.
 *
 * <p>Fix: redirect this ONE call site's {@code isCamera()} result to {@code
 * true} while Freecam is active, so {@code sendMovementPackets()} keeps
 * behaving exactly as if the camera were still attached to the player. The
 * other {@code isCamera()} call site ({@code tickMovementInput()}'s
 * movement-key gate) is untouched by this mixin -- movement-key routing
 * still works exactly as row 112 already established, only network position
 * sync is restored.
 */
@Mixin(ClientPlayerEntity.class)
abstract class ClientPlayerEntityFreecamKeepPositionSyncMixin {

    @Shadow
    protected abstract boolean isCamera();

    @Redirect(method = "sendMovementPackets", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;isCamera()Z"))
    private boolean lazuli$forcePositionSyncDuringFreecam() {
        return this.isCamera() || TweakEngineHandoff.require().isFreecamActive();
    }
}
