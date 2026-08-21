package de.lazuli.tweaks;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;

/**
 * Tweaks spec T14 (Freecam): the phantom camera object (spec Architecture,
 * "The phantom camera object should be a real, custom Entity subclass").
 * Constructed fresh by {@link FreecamTicker} each time Freecam toggles on,
 * never added to the world ({@code World.spawnEntity} is never called), and
 * driven manually once per client tick by {@link FreecamTicker} via {@link
 * #lazuli$integrate}. Every simulation method not relevant to being a
 * camera/collision carrier is reduced to a safe no-op.
 *
 * <p><strong>Confirmed via {@code javap} against this module's own resolved
 * merged Minecraft jar:</strong> {@code Entity}'s only abstract methods are
 * {@code initDataTracker}, {@code damage}, {@code readCustomData}/{@code
 * writeCustomData} -- a small, contained no-op surface (spec Open Question
 * 4, resolved: no other overrides were needed for this to construct and
 * drive safely). {@code EntityType.MARKER} is reused as the backing type
 * (never registered as its own type -- this entity is never spawned into the
 * world or synced to anything that would care about its real type), which
 * incidentally also gives it {@code MarkerEntity}'s own zero-size bounding
 * box; the block-collision sweep below therefore acts on a point rather than
 * a body-sized box, a deliberate v1 simplification (a real body-sized box is
 * a natural, isolated follow-up, not a blocker for this feature's core
 * behavior).
 *
 * <p><strong>Block collision for {@code noclip = false}</strong> reuses
 * {@code Entity}'s own public static {@code adjustMovementForCollisions}
 * helper together with {@code World.getBlockCollisions(Entity, Box)} (spec
 * Architecture) -- confirmed via {@code javap} that {@code
 * adjustMovementForCollisions} is {@code public static}, not merely {@code
 * protected} as the spec's own draft assumed, so no subclass privilege is
 * even required for this part; kept as a subclass regardless, per the
 * plan's architecture, since {@code MinecraftClient.setCameraEntity} still
 * requires a real {@code Entity}. Only ever queries block collision shapes
 * -- {@code World.getBlockCollisions} is a {@code CollisionView} default
 * method confirmed via {@code javap -c} to delegate only to {@code
 * getBlockOrFluidCollisions}, never to {@code World.getEntityCollisions} --
 * so entity collision is excluded by construction (spec Architecture), not
 * by extra suppression code. (The now-unused {@code Entity.findCollisions}
 * static helper was rejected here specifically because {@code javap -c}
 * showed it merges in {@code World.getEntityCollisions} internally.)
 */
public final class FreecamCameraEntity extends Entity {

    public FreecamCameraEntity(World world) {
        super(EntityType.MARKER, world);
        this.noClip = true;
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        // No synced data -- never added to the world, so nothing to sync.
    }

    @Override
    public boolean damage(ServerWorld world, DamageSource source, float amount) {
        return false;
    }

    @Override
    protected void readCustomData(ReadView view) {
        // Never persisted.
    }

    @Override
    protected void writeCustomData(WriteView view) {
        // Never persisted.
    }

    /**
     * Advances this phantom camera by {@code desiredDelta} (already yaw/
     * pitch-oriented world-space units for this tick), clipped against block
     * collision when {@code noclip == false}. Also seeds the interpolation
     * ("last render"/"last rotation") fields to the pre-move state so {@code
     * Camera.update}'s per-frame {@code tickProgress} lerp is smooth rather
     * than jumping once per client tick (spec Architecture: mouse-look reuse
     * needs no new code, but position/rotation interpolation for a
     * manually-driven, never-ticked entity does).
     *
     * <p><strong>Real bug fix, confirmed via {@code javap -c} against this
     * module's own resolved merged Minecraft jar:</strong> a prior version
     * of this method manually assigned only {@code lastRenderX}/{@code
     * lastRenderY}/{@code lastRenderZ}, assuming those were "the"
     * old-position fields {@code Camera} reads for render interpolation.
     * They are NOT -- {@code Entity} separately declares a second field
     * trio, {@code lastX}/{@code lastY}/{@code lastZ}, and {@code
     * Camera}'s per-frame position update interpolates via {@code
     * Mth.lerp(tickProgress, entity.lastX, entity.getX())} (confirmed via
     * {@code javap -c} on {@code Camera.class}) -- it never reads {@code
     * lastRenderX}/{@code lastRenderY}/{@code lastRenderZ} at all. Since
     * this phantom camera is never added to the world and never goes
     * through {@code Entity}'s own tick loop (the only place vanilla
     * normally keeps both field trios in sync together, via the private
     * {@code Entity.setLastPosition(Vec3d)}, reached through the public
     * {@code Entity.resetPosition()}), {@code lastX}/{@code lastY}/{@code
     * lastZ} were permanently stuck at {@code 0.0} for this camera's entire
     * lifetime, so every frame interpolated between world origin and the
     * camera's real position -- observed in-game as the camera rapidly
     * flickering between the player's actual position and {@code (0, 0,
     * 0)}. Fix: call {@code Entity}'s own public {@code resetPosition()}
     * (confirmed {@code public final}, callable directly, no mixin needed),
     * which correctly seeds {@code lastRenderX}/{@code lastRenderY}/{@code
     * lastRenderZ} AND {@code lastX}/{@code lastY}/{@code lastZ} AND {@code
     * lastYaw}/{@code lastPitch} together from the current (pre-move)
     * position/rotation, exactly the same mechanism vanilla's own per-tick
     * entity bookkeeping uses.
     */
    void lazuli$integrate(Vec3d desiredDelta, float yaw, float pitch, boolean noclip) {
        this.resetPosition();
        this.setYaw(yaw);
        this.setPitch(pitch);

        Vec3d delta = desiredDelta;
        if (!noclip && desiredDelta.lengthSquared() > 0.0) {
            Box searchBox = this.getBoundingBox().stretch(desiredDelta);
            List<net.minecraft.util.shape.VoxelShape> collisions = new java.util.ArrayList<>();
            this.getEntityWorld().getBlockCollisions(this, searchBox).forEach(collisions::add);
            delta = Entity.adjustMovementForCollisions(this, desiredDelta, this.getBoundingBox(),
                    this.getEntityWorld(), collisions);
        }
        this.setPosition(this.getX() + delta.x, this.getY() + delta.y, this.getZ() + delta.z);
    }

    /**
     * Exposes {@code Entity}'s own inherited {@code protected static}
     * movement-input-to-world-space-velocity helper (the same one every
     * living entity's own {@code travel(Vec3d)} uses to turn a (strafe,
     * vertical, forward) input vector into a yaw-relative velocity) for
     * {@link FreecamTicker} to reuse -- confirmed via {@code javap} present
     * with this exact signature. {@code FreecamTicker} itself cannot call
     * the inherited method directly (it is not an {@code Entity} subclass),
     * hence this thin static passthrough.
     */
    static Vec3d lazuli$computeVelocity(Vec3d movementInput, float speed, float yaw) {
        return movementInputToVelocity(movementInput, speed, yaw);
    }
}
