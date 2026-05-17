package ipl.sable.mixin;

import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Workaround for a Sable initialization bug:
 * {@code Entity.sable$collisionInfo} (a {@code @Unique} field on Sable's
 * {@code entity_sublevel_collision.EntityMixin}) defaults to {@code null} and is only
 * populated by Sable's {@code @Redirect} on {@code Entity.collide()} inside
 * {@link Entity#move(MoverType, Vec3)}. Sable's {@code @WrapOperation} on
 * {@code setOnGroundWithMovement()} in the same {@code move()} method then reads
 * that field unconditionally.
 *
 * <p><b>The bug:</b> vanilla {@code Entity.move} short-circuits the
 * {@code this.collide(movement)} call when {@code movement.lengthSqr() < 1.0E-7}.
 * For an entity that's never moved with non-zero displacement (just constructed,
 * freshly teleported via {@code changeEntityDimension}, etc.), the field stays
 * {@code null}. The next non-noPhysics {@code move(SELF, Vec3.ZERO)} call -- common
 * for idle animals on flat ground -- skips Sable's redirect and goes straight to
 * Sable's wrap, which dereferences the null field -> NPE:
 * <pre>
 *   Cannot read field "horizontalCollision" because "this.sable$collisionInfo" is null
 *     at Entity.wrapOperation$cai000$sable$moveInject(Entity.java:...)
 *     at Entity.move
 *     at LivingEntity.handleRelativeFrictionAndCalculateMovement
 *     at LivingEntity.travel
 *     at LivingEntity.aiStep
 *     ...
 * </pre>
 *
 * <p>We hit this when {@code SableTransitOps.teleportRiders} sends an idle animal
 * (e.g., a pig riding the airship deck) through a portal. NeoForge's
 * {@code changeEntityDimension} constructs a new {@code Entity} instance on the
 * destination side; that fresh instance has the {@code @Unique} field at its
 * default {@code null}, and the first tick where {@code travel()} computes zero
 * movement immediately NPEs.
 *
 * <p><b>The fix:</b> at HEAD of {@code Entity.move}, before vanilla's
 * lengthSqr check, if the entity is not noPhysics, not a passenger, the movement
 * is below vanilla's threshold, AND {@code sable$collisionInfo} is still null,
 * substitute a sub-millimeter downward drift {@code (0, -5e-4, 0)} (lengthSqr ~=
 * 2.5e-7, just above vanilla's 1e-7 cutoff). This forces vanilla to call
 * {@code this.collide(movement)}, Sable's {@code @Redirect} fires and populates
 * the field, and subsequent {@code setOnGroundWithMovement} sees a non-null field.
 *
 * <p>Drift cost: 0.5mm downward on the entity's first idle tick after creation.
 * Imperceptible visually, gets absorbed by vanilla gravity on the same tick.
 *
 * <p>Mixin priority {@code 900} is intentionally lower than Sable's {@code 1100}
 * (mixin uses lower number = higher priority = applied earlier in the pipeline).
 * Our {@code @ModifyVariable} runs before Sable sees the {@code movement} arg, so
 * Sable's downstream redirects/wraps see our bumped value.
 *
 * <p>This should be reported upstream to Sable -- our compat layer is patching a
 * bug that affects all Sable users, not just IP+Sable. TODO file a Sable issue.
 */
@Mixin(value = Entity.class, priority = 900)
public abstract class SableCollisionInfoInitMixin {

    @ModifyVariable(
        method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
        at = @At("HEAD"),
        argsOnly = true,
        require = 0  // best-effort: if the method signature changes, fall back to no-op
    )
    private Vec3 ipl$bumpMovementToInitSableCollisionInfo(Vec3 movement) {
        Entity self = (Entity) (Object) this;

        // Skip entities that can't trigger the bug:
        // - noPhysics entities early-return from move() before reaching the wrap site.
        // - Passengers are moved by their vehicle; their move() is rarely the path.
        if (self.noPhysics || self.isPassenger()) {
            return movement;
        }

        // If movement is non-trivial, vanilla will call collide() and Sable's
        // redirect fires naturally. No bump needed.
        if (movement.lengthSqr() >= 1.0E-7) {
            return movement;
        }

        // Only bump when sable$collisionInfo is null, to avoid jitter on every idle
        // tick after the field is populated.
        if (self instanceof EntityMovementExtension ext
            && ext.sable$getCollisionInfo() == null) {
            return new Vec3(0.0, -5.0E-4, 0.0);
        }

        return movement;
    }
}
