package de.lazuli.tweaks;

import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * Tweaks spec T14 (Freecam): 26.1 Mojmap port of {@code fabric-1.21.11}'s
 * {@code FreecamCameraEntity} -- same class Javadoc rationale applies, see
 * that file. Confirmed via {@code javap} against this module's own resolved
 * merged Minecraft jar: {@code Entity}'s abstract-method surface, {@code
 * EntityType.MARKER}, and the public static {@code collideBoundingBox}
 * collision helper all exist with the same shape, just Mojmap-renamed. Note
 * {@code noClip} is itself renamed {@code noPhysics} on this mapping (a real
 * field-name divergence, not just the expected class renames).
 *
 * <p><strong>Block-only collision.</strong> {@code Entity.collectAllColliders}
 * (the direct Mojmap counterpart of yarn's {@code Entity.findCollisions})
 * was rejected here: confirmed via {@code javap -c} that it merges in
 * {@code Level.getEntityCollisions} internally. Instead this uses {@code
 * Level.getBlockCollisions(Entity, AABB)} -- a {@code CollisionGetter}
 * default method confirmed via {@code javap -c} to delegate only to {@code
 * getBlockCollisionsFromContext}, never to {@code getEntityCollisions} --
 * so entity collision is excluded by construction, matching spec T14
 * regardless of {@code noPhysics}'s value.
 */
public final class FreecamCameraEntity extends Entity {

    public FreecamCameraEntity(Level level) {
        super(EntityType.MARKER, level);
        this.noPhysics = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // No synced data -- never added to the world, so nothing to sync.
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        // Never persisted.
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        // Never persisted.
    }

    /**
     * Advances this phantom camera by {@code desiredDelta}, clipped against
     * block collision when {@code noclip == false}. Also seeds the
     * interpolation ("old" position/rotation) fields to the pre-move state
     * so {@code Camera.update}'s per-frame partial-tick lerp is smooth
     * rather than jumping once per client tick.
     */
    void lazuli$integrate(Vec3 desiredDelta, float yaw, float pitch, boolean noclip) {
        this.xOld = this.getX();
        this.yOld = this.getY();
        this.zOld = this.getZ();
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
        this.setYRot(yaw);
        this.setXRot(pitch);

        Vec3 delta = desiredDelta;
        if (!noclip && desiredDelta.lengthSqr() > 0.0) {
            AABB searchBox = this.getBoundingBox().expandTowards(desiredDelta);
            List<VoxelShape> collisions = new java.util.ArrayList<>();
            this.level().getBlockCollisions(this, searchBox).forEach(collisions::add);
            delta = Entity.collideBoundingBox(this, desiredDelta, this.getBoundingBox(), this.level(), collisions);
        }
        this.setPos(this.getX() + delta.x, this.getY() + delta.y, this.getZ() + delta.z);
    }

    /**
     * Exposes {@code Entity}'s own inherited {@code protected static}
     * movement-input-to-world-space-velocity helper ({@code getInputVector}
     * on this mapping) for {@link FreecamTicker} to reuse -- see the
     * 1.21.11 copy's Javadoc for full rationale.
     */
    static Vec3 lazuli$computeVelocity(Vec3 movementInput, float speed, float yaw) {
        return getInputVector(movementInput, speed, yaw);
    }
}
