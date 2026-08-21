package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.player.LocalPlayer;

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
 * module's own resolved merged Minecraft jar:</strong> {@code
 * LocalPlayer.sendPosition()} (private, called unconditionally every client
 * tick from {@code LocalPlayer.tick()} whenever the player is not a
 * passenger -- confirmed via bytecode trace of the caller) gates its ENTIRE
 * body -- not just the outgoing {@code ServerboundMovePlayerPacket}
 * variants, but also the {@code xLast}/{@code yLast}/{@code zLast}/{@code
 * yRotLast}/{@code xRotLast}/{@code lastOnGround}/{@code
 * lastHorizontalCollision} bookkeeping used to compute next tick's delta --
 * behind {@code isControlledCamera()} (the exact same {@code
 * minecraft.getCameraEntity() == this} gate row 112 already documented for
 * {@code applyInput()}, confirmed via {@code javap -c}: an {@code ifeq}
 * jumps straight past the entire method body to its final {@code return}
 * when the check is false). Row 112's finding that this gate is "exactly
 * the mechanism needed" for movement-key routing is correct for {@code
 * applyInput()}/{@code aiStep()}'s flying-vertical block, but {@code
 * sendPosition()} is a third, unrelated call site the same gate also
 * reaches -- an unwanted side effect, not a documented one.
 *
 * <p>Once Freecam's {@code FreecamTicker} points {@code
 * Minecraft.setCameraEntity} away from the real player, this gate silently
 * stops the real player's own position/rotation/ground-status from ever
 * being networked to the server while Freecam is active, even though the
 * spec requires the player to keep moving locally exactly as normal
 * (gravity, fall damage, knockback, fluid drag, etc.). When Freecam
 * deactivates and the gate reopens, {@code sendPosition()} computes its
 * delta against the stale {@code xLast}/{@code yLast}/{@code zLast} last
 * written before Freecam activated, producing one large single-packet
 * "teleport" that a server-side anti-cheat plugin can reject/correct --
 * observed in-game as a screen-flash/rubber-band and an anti-cheat log
 * line rejecting an abnormal position.
 *
 * <p>Fix: redirect this ONE call site's {@code isControlledCamera()} result
 * to {@code true} while Freecam is active, so {@code sendPosition()} keeps
 * behaving exactly as if the camera were still attached to the player. The
 * other two {@code isControlledCamera()} call sites ({@code applyInput()}'s
 * movement-key gate, {@code aiStep()}'s creative-flight vertical-key gate)
 * are untouched by this mixin -- movement-key routing still works exactly
 * as row 112 already established, only network position sync is restored.
 */
@Mixin(LocalPlayer.class)
abstract class LocalPlayerFreecamKeepPositionSyncMixin {

    @Shadow
    protected abstract boolean isControlledCamera();

    @Redirect(method = "sendPosition", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;isControlledCamera()Z"))
    private boolean lazuli$forcePositionSyncDuringFreecam() {
        return this.isControlledCamera() || TweakEngineHandoff.require().isFreecamActive();
    }
}
