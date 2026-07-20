package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Make Sable's physics chunk-ticket manager parent-aware for hosted sub-levels.
 *
 * <p>Two problems with a sub-level whose container lives in {@code ipl_sable:sublevels} but
 * whose pose is in parent-dim coordinates:
 *
 * <ol>
 *   <li><b>Instant unload:</b> {@code isChunkLoadedEnough(hostingLevel, poseChunk)} is false
 *       (no players, no tickets, pose coords aren't in the plot grid), so
 *       {@code update()} would immediately {@code moveToUnloaded} every hosted ship, and the
 *       ticket cleanup pass would thrash terrain sections every tick. Fix: the hosting dim is
 *       always "loaded enough" — hosted ships stay active; stale terrain tickets still expire
 *       through the 20-tick outdated path.</li>
 *   <li><b>Terrain collision:</b> {@code addTicket} feeds {@code level.getChunk(x,z)} sections
 *       into the Rapier pipeline — the terrain the airship collides with. Reading the hosting
 *       dim would enroll void chunks. Fix: inside the per-sub-level loop, read the chunk from
 *       the sub-level's PARENT dim (recomputing the section index against the parent's height
 *       profile — nether and overworld differ) and feed it to the HOSTING pipeline. The ship's
 *       pose and the terrain coords share the parent's frame, so collision is consistent.</li>
 * </ol>
 *
 * <p>Known accepted limitation: ships from different parent dims share the hosting Rapier
 * scene, so two ships whose parent-frame positions overlap would see each other's terrain
 * copies. Revisit with per-parent scene keys if it ever matters in practice.
 */
@Pseudo
@Mixin(value = PhysicsChunkTicketManager.class, remap = false)
public abstract class IplHostedTicketManagerMixin {

    @ModifyReturnValue(method = "isChunkLoadedEnough", at = @At("RETURN"), require = 1)
    private static boolean ipl$hostingDimAlwaysLoadedEnough(
        boolean original, ServerLevel level, int x, int z
    ) {
        if (original) return true;
        return IplDimAgnostic.isHostingLevel(level);
    }

    @Unique
    private static long ipl$lastTerrainLogMs = 0;

    /**
     * Pre-enroll PARENT-dim terrain sections for every hosted sub-level, before the vanilla
     * per-sub-level loop runs. {@code addSectionIfNotTracked} registers the section with the
     * HOSTING pipeline and creates the ticket; the original loop's {@code addTicket} then
     * finds an existing ticket for the same SectionPos and only refreshes its
     * last-inhabited tick (never re-reading the hosting dim's void chunks). The bounds math
     * mirrors the original loop exactly (incl. the falling-velocity prediction) so every
     * SectionPos the loop touches is pre-enrolled with real terrain.
     */
    @Inject(method = "update", at = @At("HEAD"), require = 1)
    private void ipl$preEnrollParentTerrain(
        ServerLevel level,
        ServerSubLevelContainer container,
        SubLevelPhysicsSystem system,
        PhysicsPipeline pipeline,
        double timeStep,
        CallbackInfo ci
    ) {
        // Per-scene model: the hosting scene holds no terrain at all — the parent's own
        // manager enrolls terrain natively (ipl$enrollHostedShipTerrain below).
        if (ipl.sable.dim.IplSceneOwnership.isEnabled()) {
            return;
        }
        if (!IplDimAgnostic.isHostingLevel(level)) {
            return;
        }

        PhysicsChunkTicketManager self = (PhysicsChunkTicketManager) (Object) this;
        BoundingBox3d b = new BoundingBox3d();
        BoundingBox3d b2 = new BoundingBox3d();
        Vector3d velocity = new Vector3d();
        int hostedCount = 0;
        ServerLevel firstParent = null;

        for (int i = 0; i < container.getAllSubLevels().size(); i++) {
            ServerSubLevel subLevel = container.getAllSubLevels().get(i);
            if (subLevel.isRemoved()) continue;

            ServerLevel parent = IplDimAgnostic.getServerParentLevel(subLevel);
            if (parent == null) continue;
            hostedCount++;
            if (firstParent == null) firstParent = parent;

            // Same bounds expansion as the original loop, so coverage is identical.
            b.set(subLevel.boundingBox());
            b2.set(b);
            if (subLevel.lastPose().position()
                .distanceSquared(subLevel.logicalPose().position()) > 0.05 * 0.05) {
                system.getPipeline().getLinearVelocity(subLevel, velocity.zero()).mul(timeStep);
                b2.move(0.0, Mth.clamp(velocity.y,
                    -PhysicsChunkTicketManager.MAX_PREDICTION_DISTANCE,
                    PhysicsChunkTicketManager.MAX_PREDICTION_DISTANCE), 0.0);
                b.expandTo(b2);
            }
            b.expand(1.0, b);

            BoundingBox3i chunkBounds = b.chunkBoundsFrom();
            // The pipeline's voxel bake re-reads block content through its LevelAccelerator
            // (bound to the hosting level) — the section argument's content is ignored. The
            // override makes those reads come from the PARENT level for the duration of the
            // enrollment, so the baked collider is real terrain instead of hosting-dim void.
            ipl.sable.transit.IplTerrainReadOverride.set(parent);
            try {
                for (int x = chunkBounds.minX(); x <= chunkBounds.maxX(); x++) {
                    for (int z = chunkBounds.minZ(); z <= chunkBounds.maxZ(); z++) {
                        LevelChunk parentChunk;
                        try {
                            parentChunk = parent.getChunk(x, z);
                        } catch (Throwable t) {
                            continue; // parent chunk unavailable; original loop adds a void filler
                        }
                        for (int y = chunkBounds.minY(); y <= chunkBounds.maxY(); y++) {
                            int parentIndex = parent.getSectionIndexFromSectionY(y);
                            if (parentIndex < 0 || parentIndex >= parent.getSectionsCount()) continue;
                            self.addSectionIfNotTracked(
                                level, parentChunk.getSection(parentIndex), SectionPos.of(x, y, z), pipeline);
                        }
                    }
                }
            } finally {
                ipl.sable.transit.IplTerrainReadOverride.clear();
            }
        }

        long now = System.currentTimeMillis();
        if (hostedCount > 0 && now - ipl$lastTerrainLogMs > 5000) {
            ipl$lastTerrainLogMs = now;
            org.slf4j.LoggerFactory.getLogger("ipl-hosted-terrain").info(
                "[IPL-HOSTED-TERRAIN] pre-enrolled parent terrain for {} hosted sub-level(s), firstParent={}",
                hostedCount, firstParent.dimension().location());
        }
    }

    // ======================================================================
    // Per-scene model (spec §2.2 phase 1): terrain enrolls in the PARENT's
    // own manager — native chunks, native coords, native height profile.
    // ======================================================================

    @org.spongepowered.asm.mixin.Shadow(remap = false)
    private java.util.Map<SectionPos, dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicket>
        physicsChunks;

    /**
     * Enroll terrain sections around every hosted ship whose PARENT is this manager's
     * level. The stock per-sub-level loop never sees hosted ships (they live in the
     * hosting container), so this replicates exactly its ticket semantics for them:
     * get-or-create the ticket (feeding the section to THIS pipeline on creation — real
     * chunk, no read override) and REFRESH lastInhabitedTick, so the 20-tick expiry
     * doesn't churn terrain in and out of the scene.
     *
     * <p>Deliberately NOT replicated: the moveToUnloaded branch. Holding/unload semantics
     * for hosted ships stay with the hosting container (IplHostedHoldingMixin et al.).
     */
    @Inject(method = "update", at = @At("HEAD"), require = 1)
    private void ipl$enrollHostedShipTerrain(
        ServerLevel level,
        ServerSubLevelContainer container,
        SubLevelPhysicsSystem system,
        PhysicsPipeline pipeline,
        double timeStep,
        CallbackInfo ci
    ) {
        if (!ipl.sable.dim.IplSceneOwnership.isEnabled()) return;
        if (IplDimAgnostic.isHostingLevel(level)) return;

        dev.ryanhcode.sable.api.sublevel.SubLevelContainer hostingContainer =
            IplDimAgnostic.getHostingContainerFor(level);
        if (hostingContainer == null) return;

        BoundingBox3d b = new BoundingBox3d();
        BoundingBox3d b2 = new BoundingBox3d();
        Vector3d velocity = new Vector3d();
        long gameTime = level.getGameTime();
        int enrolledShips = 0;

        for (dev.ryanhcode.sable.sublevel.SubLevel anySub : hostingContainer.getAllSubLevels()) {
            if (!(anySub instanceof ServerSubLevel subLevel)) continue;
            if (subLevel.isRemoved()) continue;
            if (IplDimAgnostic.getServerParentLevel(subLevel) != level) continue;
            enrolledShips++;

            // Same bounds expansion as the stock loop (incl. fall-velocity prediction);
            // this pipeline owns the body under per-scene, so the velocity read is local.
            b.set(subLevel.boundingBox());
            b2.set(b);
            if (subLevel.lastPose().position()
                .distanceSquared(subLevel.logicalPose().position()) > 0.05 * 0.05) {
                pipeline.getLinearVelocity(subLevel, velocity.zero()).mul(timeStep);
                b2.move(0.0, Mth.clamp(velocity.y,
                    -PhysicsChunkTicketManager.MAX_PREDICTION_DISTANCE,
                    PhysicsChunkTicketManager.MAX_PREDICTION_DISTANCE), 0.0);
                b.expandTo(b2);
            }
            b.expand(1.0, b);

            ipl$enrollSections(level, pipeline, b, gameTime);
        }

        // Straddle CLONE bodies whose DESTINATION is this level: the clone needs terrain
        // around the portal-mapped region (ship bounds ⊕ offset). Nothing else enrolls it —
        // the straddler's parent is still the source side, and the clone is pure native
        // state no container knows about.
        int[] cloneRegions = {0};
        ipl.sable.transit.IplStraddleCloneBody.forEachSessionInto(level, (ship, mapping) -> {
            // Enclosing AABB of the portal-mapped ship bounds (rotation-capable: the 8
            // corners go through the full isometry).
            BoundingBox3d cb = mapping.mapAabb(ship.boundingBox());
            cb.expand(1.0, cb);
            ipl$enrollSections(level, pipeline, cb, gameTime);
            cloneRegions[0]++;
        });
        enrolledShips += cloneRegions[0];

        long now = System.currentTimeMillis();
        if (enrolledShips > 0 && now - ipl$lastTerrainLogMs > 5000) {
            ipl$lastTerrainLogMs = now;
            org.slf4j.LoggerFactory.getLogger("ipl-hosted-terrain").info(
                "[IPL-SCENE-TERRAIN] {} enrolled native terrain for {} hosted ship/clone region(s)",
                level.dimension().location(), enrolledShips);
        }
    }

    /**
     * Get-or-create + REFRESH tickets for every section in {@code bounds} (the stock
     * addTicket semantics — refresh matters: without it the 20-tick expiry churns terrain
     * in and out of the scene).
     */
    @Unique
    private void ipl$enrollSections(
        ServerLevel level, PhysicsPipeline pipeline, BoundingBox3d bounds, long gameTime
    ) {
        BoundingBox3i chunkBounds = bounds.chunkBoundsFrom();
        for (int x = chunkBounds.minX(); x <= chunkBounds.maxX(); x++) {
            for (int z = chunkBounds.minZ(); z <= chunkBounds.maxZ(); z++) {
                if (!PhysicsChunkTicketManager.isChunkLoadedEnough(level, x, z)) continue;
                for (int y = chunkBounds.minY(); y <= chunkBounds.maxY(); y++) {
                    int index = level.getSectionIndexFromSectionY(y);
                    if (index < 0 || index >= level.getSectionsCount()) continue;

                    SectionPos sectionPos = SectionPos.of(x, y, z);
                    dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicket ticket =
                        this.physicsChunks.get(sectionPos);
                    if (ticket == null) {
                        LevelChunk chunkAt = level.getChunk(x, z);
                        pipeline.handleChunkSectionAddition(
                            chunkAt.getSection(index), x, y, z, false);
                        ticket = new dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicket(
                            sectionPos, gameTime, null);
                        this.physicsChunks.put(sectionPos, ticket);
                    }
                    ticket.setLastInhabitedTick(gameTime);
                }
            }
        }
    }
}
