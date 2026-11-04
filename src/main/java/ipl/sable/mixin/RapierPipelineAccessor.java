package ipl.sable.mixin;

import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsScene;
import dev.ryanhcode.sable.physics.impl.rapier.collider.RapierVoxelColliderBakery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Access to the pipeline's collider bakery for portal rim geometry. Native collider handles
 * are process-global and valid in every chart.
 */
@Pseudo
@Mixin(value = RapierPhysicsPipeline.class, remap = false)
public interface RapierPipelineAccessor {

    @Accessor(value = "colliderBakery")
    RapierVoxelColliderBakery ipl$colliderBakery();

    /** Sable 2.0: scene ids are native {@code long} handles held by the pipeline. */
    @Invoker(value = "getSceneHandle")
    long ipl$sceneHandle();

    /**
     * Raw scene FIELD — null once the pipeline is torn down (or never armed). Use
     * this for liveness checks: {@code ipl$sceneHandle()} is an invoker into
     * {@code getSceneHandle}, which dereferences the scene and NPEs when null.
     */
    @Accessor(value = "scene")
    RapierPhysicsScene ipl$scene();

}
