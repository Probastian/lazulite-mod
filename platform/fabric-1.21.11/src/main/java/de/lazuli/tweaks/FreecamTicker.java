package de.lazuli.tweaks;

import de.lazuli.api.tweaks.TweakId;
import de.lazuli.features.tweaks.services.TweakRegistry;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Tweaks spec T14 (Freecam): the {@code ZoomTicker}-precedent {@code
 * ClientTickEvents.END_CLIENT_TICK} registration driving Freecam's
 * continuous per-tick camera/input logic (spec Architecture, Files to
 * Create). On toggle-on, constructs one {@link FreecamCameraEntity} and
 * calls {@code MinecraftClient.setCameraEntity}; each tick while active,
 * integrates the phantom's position from the real player's live movement
 * input + yaw/pitch; on toggle-off (including forced via the safety net),
 * restores the camera to the real player.
 *
 * <p><strong>Movement-key routing needs no dedicated mixin at all --
 * confirmed via {@code javap}/{@code javap -c} against this module's own
 * resolved merged Minecraft jar, the plan's own single highest-risk item
 * turned out to already be solved by vanilla itself:</strong> {@code
 * ClientPlayerEntity.isCamera()} (returns {@code client.getCameraEntity()
 * == this}) already gates every position-affecting consumer of {@code
 * this.input} -- {@code tickMovementInput()} (walking) and the
 * creative-flight vertical-velocity block inside {@code tickMovement()}
 * (jump/sneak while flying) both check it before reading {@code
 * this.input.playerInput}. Once {@code setCameraEntity} points away from
 * {@code client.player}, {@code isCamera()} goes false and the real player
 * stops moving from WASD/jump/sneak for free -- no mixin needed. (Residual,
 * out-of-scope-for-v1 quirk, confirmed but not fixed: a few purely cosmetic/
 * network-informational consumers of {@code this.input.playerInput} are
 * NOT gated by {@code isCamera()} -- e.g. {@code isSneaking()} and the
 * per-tick {@code PlayerInputC2SPacket} vanilla already sends whenever raw
 * input changes -- so the real player may still visually crouch/report
 * sneak-pressed while the Sneak key is reused to fly the camera down. This
 * does not move the player or send a position packet, so it does not
 * violate this feature's hard requirements, but it is not pixel-perfect.)
 */
public final class FreecamTicker {

    private static FreecamCameraEntity cameraEntity;
    private static ClientPlayerEntity lastPlayer;
    private static RegistryKey<World> lastDimension;
    private static boolean wasActive;

    private FreecamTicker() {
    }

    public static void register(TweaksKeyBindings keyBindings, TweakHooksImpl hooks, TweakRegistry registry) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> lazuli$tick(client, hooks, registry));
    }

    private static void lazuli$tick(MinecraftClient client, TweakHooksImpl hooks, TweakRegistry registry) {
        boolean active = hooks.isFreecamActive();

        if (active && lazuli$safetyNetTripped(client)) {
            registry.setEnabled(TweakId.FREECAM, false);
            active = false;
        }

        if (active && !wasActive) {
            lazuli$activate(client);
        } else if (!active && wasActive) {
            lazuli$deactivate(client);
        }

        if (active && cameraEntity != null && client.player != null) {
            lazuli$integrate(client, hooks);
        }

        wasActive = active;
        if (client.player != null) {
            lastPlayer = client.player;
        }
        if (client.world != null) {
            lastDimension = client.world.getRegistryKey();
        }
    }

    /** Disconnect, respawn (new player instance), dimension change, or death (spec Requirements T14 safety net). */
    private static boolean lazuli$safetyNetTripped(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            return true;
        }
        if (lastPlayer != null && lastPlayer != client.player) {
            return true;
        }
        if (lastDimension != null && !lastDimension.equals(client.world.getRegistryKey())) {
            return true;
        }
        return client.player.isDead() || client.player.getHealth() <= 0.0f;
    }

    private static void lazuli$activate(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            return;
        }
        FreecamCameraEntity camera = new FreecamCameraEntity(client.world);
        Vec3d startPos = client.player.getEyePos();
        camera.setPosition(startPos.x, startPos.y, startPos.z);
        camera.setYaw(client.player.getYaw());
        camera.setPitch(client.player.getPitch());
        cameraEntity = camera;
        client.setCameraEntity(camera);
    }

    private static void lazuli$deactivate(MinecraftClient client) {
        cameraEntity = null;
        if (client.player != null) {
            client.setCameraEntity(client.player);
        }
    }

    private static void lazuli$integrate(MinecraftClient client, TweakHooksImpl hooks) {
        ClientPlayerEntity player = client.player;
        PlayerInput rawInput = player.input.playerInput;

        double strafe = (rawInput.right() ? 1.0 : 0.0) - (rawInput.left() ? 1.0 : 0.0);
        double forward = (rawInput.forward() ? 1.0 : 0.0) - (rawInput.backward() ? 1.0 : 0.0);
        double vertical = (rawInput.jump() ? 1.0 : 0.0) - (rawInput.sneak() ? 1.0 : 0.0);

        float baseSpeed = player.getAbilities().getFlySpeed();
        float speed = baseSpeed * hooks.freecamMoveSpeed()
                * (rawInput.sprint() ? hooks.freecamSprintMultiplier() : 1.0f);

        Vec3d horizontal = FreecamCameraEntity.lazuli$computeVelocity(
                new Vec3d(strafe, 0.0, forward), speed, cameraEntity.getYaw());
        Vec3d delta = new Vec3d(horizontal.x, vertical * speed, horizontal.z);

        cameraEntity.lazuli$integrate(delta, player.getYaw(), player.getPitch(), hooks.freecamNoclip());
    }
}
