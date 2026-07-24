package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

/**
 * A failed constraint validation must not kill the server.
 *
 * <p>Mods re-attach persisted constraints from BE ticks with STORED plot-coordinate
 * anchors (Simulated's swivel bearing). A stale anchor — e.g. from a save written before
 * same-slot rehoming, where the split part's plot moved — made Sable's
 * {@code validateAnchors} throw out of a ticking block entity: whole-server crash. The
 * anchors are data, not code invariants; treat the failure like the mod's own
 * "constraint couldn't attach" path: warn (throttled) and return null. Callers already
 * handle a null handle (Simulated re-checks persistence and retries).
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline", remap = false)
public abstract class IplConstraintValidationSoftFailMixin {

    @Unique
    private static long ipl$lastConstraintFailLogMs = 0;

    @WrapMethod(method = "addConstraint", remap = false)
    @Nullable
    private <T extends PhysicsConstraintHandle> T ipl$softFailValidation(
        @Nullable PhysicsPipelineBody bodyA, @Nullable PhysicsPipelineBody bodyB,
        PhysicsConstraintConfiguration<T> configuration, Operation<T> original
    ) {
        try {
            return original.call(bodyA, bodyB, configuration);
        } catch (IllegalArgumentException e) {
            long now = System.currentTimeMillis();
            if (now - ipl$lastConstraintFailLogMs > 2000) {
                ipl$lastConstraintFailLogMs = now;
                org.slf4j.LoggerFactory.getLogger("ipl-constraint").warn(
                    "[IPL-CONSTRAINT] rejected invalid constraint instead of crashing: {}",
                    e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            }
            return null;
        }
    }
}
