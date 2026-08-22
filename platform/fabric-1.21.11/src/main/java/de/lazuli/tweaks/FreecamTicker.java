package de.lazuli.tweaks;

import de.lazuli.api.tweaks.TweakId;
import de.lazuli.features.tweaks.services.TweakRegistry;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Tweaks spec T14 (Freecam): the {@code ZoomTicker}-precedent {@code
 * ClientTickEvents.END_CLIENT_TICK} registration driving Freecam's
 * continuous per-tick camera/input logic (spec Architecture, Files to
 * Create). On toggle-on, constructs one {@link FreecamCameraEntity} and
 * calls {@code MinecraftClient.setCameraEntity}; each tick while active,
 * integrates the phantom's position from the real player's live movement
 * input; on toggle-off (including forced via the safety net), restores the
 * camera to the real player.
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
 *
 * <p><strong>Addendum AD-1:</strong> this class no longer copies the real
 * player's live yaw/pitch into the camera each tick -- {@link
 * FreecamCameraEntity} now owns its own persistent rotation, seeded once at
 * {@link #lazuli$activate} time and thereafter mutated only by mouse input
 * redirected directly to it (see {@code MouseFreecamLookRedirectMixin}).
 * {@link #cameraEntity()} exposes the active camera (or {@code null}) to
 * that mixin, package-external per this class being {@code public}.
 *
 * <p><strong>Addendum AD-2:</strong> {@link #isCameraInsidePlayerBounds()}
 * is computed once per tick here (this class already holds direct
 * references to both the phantom camera and the real player entity) and
 * consumed by the show-body mixin in place of the removed {@code
 * showOwnBody} configurable.
 *
 * <p><strong>Addendum AD-3:</strong> {@link #MOVE_SPEED_RUNTIME_SCALE}
 * compensates for the {@code moveSpeed} configurable's rescaled 0.25-5.0 UI
 * range so a migrated user's felt flight speed is unchanged -- see {@code
 * TweaksConfigIO.parse}'s {@code FREECAM}-scoped migration branch.
 *
 * <p><strong>Addendum 2 AD-8:</strong> {@link #onLocalPlayerHurt()} is called
 * by {@code LivingEntityFreecamOnHurtMixin} whenever the local player's own
 * {@code animateDamage} fires while Freecam is active -- it (a) extends/
 * resets the fixed 60-client-tick HUD-reveal window ({@link
 * #isHurtRevealActive()}, consumed by {@code InGameHudFreecamHudMixin}
 * alongside AD-7's own toggle) when {@code onHurt == HURT_INDICATOR}, and (b)
 * sets a one-tick "hurt this tick" latch that {@link #lazuli$safetyNetTripped}
 * folds into its existing disconnect/respawn/dimension-change/death
 * safety-net family when {@code onHurt == DISABLE_FREECAM}.
 */
public final class FreecamTicker {

    /** Addendum AD-3: compensates for {@code moveSpeed}'s rescaled 0.25-5.0 UI range (was 0.1-10.0). */
    private static final float MOVE_SPEED_RUNTIME_SCALE = 10.0f;

    /** Addendum AD-2: fixed inflate margin (blocks) to avoid boundary flicker -- see spec AD-2. */
    private static final double SHOW_BODY_MARGIN = 0.1;

    /** Addendum 2 AD-8: fixed 60-client-tick (3s) HUD-reveal-on-hurt window -- see spec AD-8. */
    private static final long HURT_REVEAL_TICKS = 60L;

    private static FreecamCameraEntity cameraEntity;
    private static ClientPlayerEntity lastPlayer;
    private static RegistryKey<World> lastDimension;
    private static boolean wasActive;
    private static boolean cameraInsidePlayerBounds;
    private static long tickCounter;
    private static long hurtRevealUntilTick = Long.MIN_VALUE;
    private static boolean hurtSignalPending;

    private FreecamTicker() {
    }

    public static void register(TweaksKeyBindings keyBindings, TweakHooksImpl hooks, TweakRegistry registry) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> lazuli$tick(client, hooks, registry));
    }

    /** Addendum AD-1: the active phantom camera entity, or {@code null} if Freecam is not currently active. */
    public static FreecamCameraEntity cameraEntity() {
        return cameraEntity;
    }

    /** Addendum AD-2: true if the freecam camera's current position is inside the player's own (inflated) live bounding box. */
    public static boolean isCameraInsidePlayerBounds() {
        return cameraInsidePlayerBounds;
    }

    /** Addendum 2 AD-8: true if a HUD-reveal-on-hurt window (onHurt == HURT_INDICATOR) is currently active. */
    public static boolean isHurtRevealActive() {
        return tickCounter <= hurtRevealUntilTick;
    }

    /** Addendum 2 AD-8: called by {@code LivingEntityFreecamOnHurtMixin} when the local player's own hurt animation fires while Freecam is active. */
    public static void onLocalPlayerHurt() {
        hurtSignalPending = true;
    }

    private static void lazuli$tick(MinecraftClient client, TweakHooksImpl hooks, TweakRegistry registry) {
        tickCounter++;
        boolean active = hooks.isFreecamActive();

        if (active && hurtSignalPending && hooks.freecamOnHurtShowsHurtIndicator()) {
            hurtRevealUntilTick = tickCounter + HURT_REVEAL_TICKS;
        }

        if (active && lazuli$safetyNetTripped(client, hooks)) {
            registry.setEnabled(TweakId.FREECAM, false);
            active = false;
        }
        hurtSignalPending = false;

        if (active && !wasActive) {
            lazuli$activate(client);
        } else if (!active && wasActive) {
            lazuli$deactivate(client);
        }

        if (active && cameraEntity != null && client.player != null) {
            lazuli$integrate(client, hooks);
        } else {
            cameraInsidePlayerBounds = false;
        }

        wasActive = active;
        if (client.player != null) {
            lastPlayer = client.player;
        }
        if (client.world != null) {
            lastDimension = client.world.getRegistryKey();
        }
    }

    /**
     * Disconnect, respawn (new player instance), dimension change, or death
     * (spec Requirements T14 safety net). Addendum 2 AD-8 adds a 5th
     * condition: a pending "hurt this tick" signal while {@code onHurt ==
     * DISABLE_FREECAM}.
     */
    private static boolean lazuli$safetyNetTripped(MinecraftClient client, TweakHooksImpl hooks) {
        if (client.world == null || client.player == null) {
            return true;
        }
        if (lastPlayer != null && lastPlayer != client.player) {
            return true;
        }
        if (lastDimension != null && !lastDimension.equals(client.world.getRegistryKey())) {
            return true;
        }
        if (client.player.isDead() || client.player.getHealth() <= 0.0f) {
            return true;
        }
        return hurtSignalPending && hooks.freecamOnHurtDisablesFreecam();
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
        cameraInsidePlayerBounds = false;
        if (client.player != null) {
            client.setCameraEntity(client.player);
        }
    }

    private static void lazuli$integrate(MinecraftClient client, TweakHooksImpl hooks) {
        ClientPlayerEntity player = client.player;
        PlayerInput rawInput = player.input.playerInput;

        double strafe = (rawInput.left() ? 1.0 : 0.0) - (rawInput.right() ? 1.0 : 0.0);
        double forward = (rawInput.forward() ? 1.0 : 0.0) - (rawInput.backward() ? 1.0 : 0.0);
        double vertical = (rawInput.jump() ? 1.0 : 0.0) - (rawInput.sneak() ? 1.0 : 0.0);

        float baseSpeed = player.getAbilities().getFlySpeed();
        float speed = baseSpeed * hooks.freecamMoveSpeed() * MOVE_SPEED_RUNTIME_SCALE
                * (rawInput.sprint() ? hooks.freecamSprintMultiplier() : 1.0f);

        Vec3d horizontal = FreecamCameraEntity.lazuli$computeVelocity(
                new Vec3d(strafe, 0.0, forward), speed, cameraEntity.getYaw());
        Vec3d delta = new Vec3d(horizontal.x, vertical * speed, horizontal.z);

        cameraEntity.lazuli$integrate(delta, hooks.freecamNoclip());

        Box playerBox = player.getBoundingBox().expand(SHOW_BODY_MARGIN);
        cameraInsidePlayerBounds = playerBox.contains(cameraEntity.getX(), cameraEntity.getY(), cameraEntity.getZ());
    }
}
