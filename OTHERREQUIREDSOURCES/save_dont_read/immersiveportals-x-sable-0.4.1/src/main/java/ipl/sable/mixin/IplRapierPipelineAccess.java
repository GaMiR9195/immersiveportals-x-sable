package ipl.sable.mixin;

import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.physics.impl.rapier.collider.RapierVoxelColliderBakery;
import dev.ryanhcode.sable.util.LevelAccelerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Access to the pipeline's voxel-bake machinery, for building a straddle CLONE body's
 * collider data outside the pipeline ({@code IplStraddleCloneBody}): the accelerator
 * (cached level reads, honoring {@code IplTerrainReadOverride}) and the collider bakery
 * (per-blockstate native collider handles — valid in EVERY scene, the registry is
 * process-global).
 */
@Pseudo
@Mixin(value = RapierPhysicsPipeline.class, remap = false)
public interface IplRapierPipelineAccess {

    @Accessor(value = "accelerator", remap = false)
    LevelAccelerator ipl$accelerator();

    @Accessor(value = "colliderBakery", remap = false)
    RapierVoxelColliderBakery ipl$colliderBakery();

    /** Sable 2.0: scene ids are native {@code long} handles held by the pipeline. */
    @org.spongepowered.asm.mixin.gen.Invoker(value = "getSceneHandle", remap = false)
    long ipl$sceneHandle();
}
