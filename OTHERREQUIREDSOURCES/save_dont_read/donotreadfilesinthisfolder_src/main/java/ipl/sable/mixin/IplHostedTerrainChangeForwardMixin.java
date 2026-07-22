package ipl.sable.mixin;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.transit.IplTerrainReadOverride;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Forward parent-dim terrain block changes into the HOSTING physics pipeline.
 *
 * <p>{@code SubLevelPhysicsSystem.handleBlockChange} keeps the Rapier terrain voxels in sync
 * with world block changes — but only for ITS OWN pipeline. A hosted sub-level's body lives
 * in the hosting dim's pipeline, so a block changing in the parent dim (piston pushing a
 * block into an airship, a wall built in its path) never updated the terrain copy the ship
 * actually collides with: pushed blocks entered the hull without contact.
 *
 * <p>This mixin runs after the parent system's own handling: if any hosted sub-level whose
 * {@code parentLevel} is this dim is near the changed block, the voxel update is replayed
 * into the hosting pipeline (under {@link IplTerrainReadOverride}, since the pipeline's
 * neighborhood bake re-reads blocks through its own level-bound accelerator) and the nearby
 * ships' bodies are woken so contact resolution reacts immediately.
 */
@Pseudo
@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public abstract class IplHostedTerrainChangeForwardMixin {

    @Shadow @Final private ServerLevel level;

    @Inject(method = "handleBlockChange", at = @At("TAIL"), require = 0)
    private void ipl$forwardTerrainChangeToHostingPipeline(
        SectionPos sectionPos, LevelChunkSection section,
        int localX, int localY, int localZ,
        BlockState oldState, BlockState newState, CallbackInfo ci
    ) {
        if (IplDimAgnostic.isHostingLevel(this.level)) {
            return;
        }
        // Per-scene model: parent-dim block changes update the parent's OWN scene natively
        // through the stock path above — there is no hosting-scene terrain copy to sync.
        if (ipl.sable.dim.IplSceneOwnership.isEnabled()) {
            return;
        }

        SubLevelContainer hostingContainer = IplDimAgnostic.getHostingContainerFor(this.level);
        if (!(hostingContainer instanceof ServerSubLevelContainer serverHosting)) {
            return;
        }

        double bx = (sectionPos.x() << SectionPos.SECTION_BITS) + localX;
        double by = (sectionPos.y() << SectionPos.SECTION_BITS) + localY;
        double bz = (sectionPos.z() << SectionPos.SECTION_BITS) + localZ;
        // The forward region must cover everything the hosting scene may have BAKED for this
        // ship — the ticket loop enrolls sections for bounds + velocity prediction (≤20) +
        // expansion, and tickets only expire 20 ticks after the ship leaves. A too-tight gate
        // leaves stale phantom terrain (ships floating on dug-out ground / clipping into
        // newly placed blocks). 40 blocks comfortably covers the whole enrollment envelope.
        double margin = 40.0;
        BoundingBox3d blockBox = new BoundingBox3d(
            bx - margin, by - margin, bz - margin,
            bx + margin + 1, by + margin + 1, bz + margin + 1);

        List<ServerSubLevel> nearby = null;
        for (SubLevel sub : serverHosting.getAllSubLevels()) {
            if (sub.isRemoved()) continue;
            if (!(sub instanceof ServerSubLevel hosted)) continue;
            if (IplDimAgnostic.getParentLevel(hosted) != this.level) continue;
            if (!hosted.boundingBox().intersects(blockBox)) continue;
            if (nearby == null) nearby = new ArrayList<>(2);
            nearby.add(hosted);
        }
        if (nearby == null) {
            return;
        }

        PhysicsPipeline hostingPipeline = serverHosting.physicsSystem().getPipeline();
        IplTerrainReadOverride.set(this.level);
        try {
            hostingPipeline.handleBlockChange(sectionPos, section, localX, localY, localZ, oldState, newState);
        } finally {
            IplTerrainReadOverride.clear();
        }

        for (ServerSubLevel hosted : nearby) {
            hostingPipeline.wakeUp(hosted);
        }

        // TEMPORARY bring-up diagnostic (throttled): which terrain changes reach the hosting
        // scene — essential for the piston-vs-hull investigation.
        long now = System.currentTimeMillis();
        if (now - ipl$lastForwardLogMs > 500) {
            ipl$lastForwardLogMs = now;
            org.slf4j.LoggerFactory.getLogger("ipl-hosted-terrain").info(
                "[IPL-TERRAIN-FWD] ({},{},{}) {} -> {} (woke {} ship(s))",
                (int) bx, (int) by, (int) bz,
                oldState.getBlock().getName().getString(),
                newState.getBlock().getName().getString(),
                nearby.size());
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private static long ipl$lastForwardLogMs = 0;
}
