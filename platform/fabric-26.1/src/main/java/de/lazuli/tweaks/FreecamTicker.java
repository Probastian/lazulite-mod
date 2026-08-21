package de.lazuli.tweaks;

import de.lazuli.api.tweaks.TweakId;
import de.lazuli.features.tweaks.services.TweakRegistry;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Tweaks spec T14 (Freecam): 26.1 Mojmap port of {@code fabric-1.21.11}'s
 * {@code FreecamTicker} -- same class Javadoc rationale applies, see that
 * file (movement-key routing needs no dedicated mixin: {@code
 * LocalPlayer.isControlledCamera()} -- confirmed via {@code javap} to be
 * the exact Mojmap equivalent of {@code ClientPlayerEntity.isCamera()},
 * same {@code minecraft.getCameraEntity() == this} shape -- already gates
 * {@code applyInput()} the same way).
 */
public final class FreecamTicker {

    private static FreecamCameraEntity cameraEntity;
    private static LocalPlayer lastPlayer;
    private static ResourceKey<Level> lastDimension;
    private static boolean wasActive;

    private FreecamTicker() {
    }

    public static void register(TweaksKeyBindings keyBindings, TweakHooksImpl hooks, TweakRegistry registry) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> lazuli$tick(client, hooks, registry));
    }

    private static void lazuli$tick(Minecraft client, TweakHooksImpl hooks, TweakRegistry registry) {
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
        if (client.level != null) {
            lastDimension = client.level.dimension();
        }
    }

    /** Disconnect, respawn (new player instance), dimension change, or death (spec Requirements T14 safety net). */
    private static boolean lazuli$safetyNetTripped(Minecraft client) {
        if (client.level == null || client.player == null) {
            return true;
        }
        if (lastPlayer != null && lastPlayer != client.player) {
            return true;
        }
        if (lastDimension != null && !lastDimension.equals(client.level.dimension())) {
            return true;
        }
        return client.player.isDeadOrDying() || client.player.getHealth() <= 0.0f;
    }

    private static void lazuli$activate(Minecraft client) {
        if (client.player == null || client.level == null) {
            return;
        }
        FreecamCameraEntity camera = new FreecamCameraEntity(client.level);
        Vec3 startPos = client.player.getEyePosition();
        camera.setPos(startPos.x, startPos.y, startPos.z);
        camera.setYRot(client.player.getYRot());
        camera.setXRot(client.player.getXRot());
        cameraEntity = camera;
        client.setCameraEntity(camera);
    }

    private static void lazuli$deactivate(Minecraft client) {
        cameraEntity = null;
        if (client.player != null) {
            client.setCameraEntity(client.player);
        }
    }

    private static void lazuli$integrate(Minecraft client, TweakHooksImpl hooks) {
        LocalPlayer player = client.player;
        Input rawInput = player.input.keyPresses;

        double strafe = (rawInput.right() ? 1.0 : 0.0) - (rawInput.left() ? 1.0 : 0.0);
        double forward = (rawInput.forward() ? 1.0 : 0.0) - (rawInput.backward() ? 1.0 : 0.0);
        double vertical = (rawInput.jump() ? 1.0 : 0.0) - (rawInput.shift() ? 1.0 : 0.0);

        float baseSpeed = player.getAbilities().getFlyingSpeed();
        float speed = baseSpeed * hooks.freecamMoveSpeed()
                * (rawInput.sprint() ? hooks.freecamSprintMultiplier() : 1.0f);

        Vec3 horizontal = FreecamCameraEntity.lazuli$computeVelocity(
                new Vec3(strafe, 0.0, forward), speed, cameraEntity.getYRot());
        Vec3 delta = new Vec3(horizontal.x, vertical * speed, horizontal.z);

        cameraEntity.lazuli$integrate(delta, player.getYRot(), player.getXRot(), hooks.freecamNoclip());
    }
}
