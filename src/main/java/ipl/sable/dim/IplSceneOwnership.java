package ipl.sable.dim;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import ipl.sable.transit.IplTerrainReadOverride;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-scene body ownership (portal-physics spec §2.2, phase 1).
 *
 * <p>Under the per-scene model a hosted sub-level's gameplay state stays in the hosting
 * dimension, but its Rapier body lives in its PARENT dimension's scene — natural
 * coordinates, native terrain, native height profile. This class is the single source of
 * truth for which scene that is:
 *
 * <ul>
 *   <li>{@link #owningLevel}: the level whose pipeline should hold the body (parent for
 *       hosted ships; the sub-level's own level otherwise, including the fallback while a
 *       freshly-deserialized ship's parent is still unresolved).</li>
 *   <li>{@link #bodyHome}: where the body ACTUALLY is right now — recorded by the pipeline
 *       add/remove routing in {@code SableRapierPipelineOwnershipGuardMixin}.</li>
 *   <li>{@link #reconcile}: migrates any body whose home != owner (parent resolved after
 *       boot restore, parent flipped by transit). Runs once per hosting-container tick.</li>
 * </ul>
 *
 * <p>Kill switch: {@code -Dipl.sable.perScene=false} reverts to the single-hosting-scene
 * model (parent terrain fed into the hosting scene with read overrides).
 */
public final class IplSceneOwnership {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-scene-ownership");

    private static final boolean PER_SCENE =
        !"false".equalsIgnoreCase(System.getProperty("ipl.sable.perScene", "true"));

    /** Where each hosted body currently lives (recorded by the routed pipeline add/remove). */
    private static final Map<UUID, ServerLevel> bodyHome = new HashMap<>();

    private IplSceneOwnership() {}

    public static boolean isEnabled() {
        return PER_SCENE;
    }

    /** The level whose Rapier scene should own this sub-level's body. */
    public static ServerLevel owningLevel(ServerSubLevel sub) {
        if (isEnabled() && IplDimAgnostic.isHosted(sub)) {
            ServerLevel parent = IplDimAgnostic.getServerParentLevel(sub);
            if (parent != null) return parent;
        }
        return (ServerLevel) sub.getLevel();
    }

    @Nullable
    public static RapierPhysicsPipeline pipelineOf(@Nullable ServerLevel level) {
        if (level == null) return null;
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return null;
        return container.physicsSystem().getPipeline() instanceof RapierPhysicsPipeline rapier
            ? rapier : null;
    }

    @Nullable
    public static RapierPhysicsPipeline owningPipeline(ServerSubLevel sub) {
        return pipelineOf(owningLevel(sub));
    }

    // ------------------------------------------------------------------
    // Body-home bookkeeping (called from the guard mixin's add/remove routing).
    // ------------------------------------------------------------------

    public static void recordBodyAdded(ServerSubLevel sub, ServerLevel home) {
        bodyHome.put(sub.getUniqueId(), home);
    }

    public static void recordBodyRemoved(ServerSubLevel sub) {
        bodyHome.remove(sub.getUniqueId());
    }

    @Nullable
    public static ServerLevel getBodyHome(ServerSubLevel sub) {
        return bodyHome.get(sub.getUniqueId());
    }

    /** Server stopping: drop all state. */
    public static void clearAll() {
        bodyHome.clear();
    }

    // ------------------------------------------------------------------
    // Migration + reconciliation.
    // ------------------------------------------------------------------

    /**
     * Migrate a hosted body between scenes, preserving pose (the current logicalPose) and
     * velocity. Also moves the plot's voxel chunk sections — they are body-attached data
     * that must live in the body's scene.
     */
    public static void migrate(ServerSubLevel sub, ServerLevel fromLevel, ServerLevel toLevel) {
        if (fromLevel == toLevel) return;
        RapierPhysicsPipeline from = pipelineOf(fromLevel);
        RapierPhysicsPipeline to = pipelineOf(toLevel);
        if (from == null || to == null || from == to) return;

        Vector3d lin = from.getLinearVelocity(sub, new Vector3d());
        Vector3d ang = from.getAngularVelocity(sub, new Vector3d());

        removePlotSections(sub, from);
        from.remove(sub);
        recordBodyRemoved(sub);

        to.add(sub, sub.logicalPose());
        recordBodyAdded(sub, toLevel);
        // pipeline.add()'s INTERNAL onStatsChanged is cancelled by the ownership guard —
        // the body enters activeSubLevels only after it runs, so the guard judges it
        // unowned. Re-fire now that the body is owned: setLocalBounds/setCenterOfMass
        // must reach native BEFORE any chunk insert (the native insert_block unwraps
        // local_bounds — lib.rs:218 — and hard-aborts the process without them).
        to.onStatsChanged(sub);
        feedPlotSections(sub, to);
        to.addLinearAndAngularVelocity(sub, lin, ang);
        to.wakeUp(sub);
    }

    /**
     * Feed every loaded, non-air plot chunk section into {@code pipeline}'s scene. The
     * pipeline's voxel bake re-reads content through its level-bound accelerator, so the
     * hosting level (where plot chunks physically live) is installed as the read override.
     * Mirrors {@code SubLevelPhysicsSystem.recoverSubLevel}'s re-feed loop.
     */
    public static void feedPlotSections(ServerSubLevel sub, PhysicsPipeline pipeline) {
        ServerLevelPlot plot = sub.getPlot();
        IplTerrainReadOverride.set(sub.getLevel());
        try {
            for (PlotChunkHolder holder : plot.getLoadedChunks()) {
                LevelChunk chunk = holder.getChunk();
                ChunkPos global = chunk.getPos();
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < chunk.getSectionsCount(); i++) {
                    LevelChunkSection section = sections[i];
                    if (!section.hasOnlyAir()) {
                        int sectionY = chunk.getSectionYFromSectionIndex(i);
                        pipeline.handleChunkSectionAddition(section, global.x, sectionY, global.z, true);
                    }
                }
            }
        } finally {
            IplTerrainReadOverride.clear();
        }
    }

    /** Remove every loaded plot chunk section from {@code pipeline}'s scene. */
    public static void removePlotSections(ServerSubLevel sub, PhysicsPipeline pipeline) {
        ServerLevelPlot plot = sub.getPlot();
        for (PlotChunkHolder holder : plot.getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            ChunkPos global = chunk.getPos();
            for (int i = 0; i < chunk.getSectionsCount(); i++) {
                int sectionY = chunk.getSectionYFromSectionIndex(i);
                pipeline.handleChunkSectionRemoval(global.x, sectionY, global.z);
            }
        }
    }

    private static long lastReconcileLogMs = 0;

    /**
     * Move any hosted body whose current scene doesn't match its owner. Covers: parent
     * resolved after boot restore (body landed in the hosting scene as fallback), and
     * parent flipped by transit (called explicitly there, but this self-heals any missed
     * path). Runs once per hosting-container tick.
     */
    public static void reconcile(ServerSubLevelContainer hostingContainer) {
        if (!isEnabled()) return;

        for (ServerSubLevel sub : hostingContainer.getAllSubLevels()) {
            if (sub.isRemoved()) continue;
            if (!IplDimAgnostic.isHosted(sub)) continue;

            ServerLevel home = getBodyHome(sub);
            ServerLevel owner = owningLevel(sub);
            if (home == null || home == owner) continue;

            long now = System.currentTimeMillis();
            if (now - lastReconcileLogMs > 1000) {
                lastReconcileLogMs = now;
                LOG.info("[IPL-SCENE] migrating body {} {} -> {}", sub.getUniqueId(),
                    home.dimension().location(), owner.dimension().location());
            }
            migrate(sub, home, owner);
        }
    }

}
