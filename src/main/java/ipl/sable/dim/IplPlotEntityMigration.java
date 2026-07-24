package ipl.sable.dim;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Migrate entities that ENTER PLOT SPACE while in a parent dimension over to the hosting
 * dimension — plot coordinates are only physically backed there.
 *
 * <p>Stock Sable keeps a ship's plot in the SAME dimension, so mods freely move entities
 * into plot coordinates to "board" a ship: Simulated's launched plunger converts its own
 * position through {@code logicalPose().transformPositionInverse} and {@code setPosRaw}s
 * itself into the plot the moment it hits a sub-level. Hosted, that position is ~20.5M
 * blocks of empty parent-dimension far land — untracked, unloaded, effectively deleted
 * ("the plunger disappears the moment it touches the ship"). This is the runtime twin of
 * {@code SableRehomeOps.relocatePlotEntities} (which covers rehome-time residents), made
 * structural: ANY entity whose position lands in the hosting plot grid while it sits in a
 * non-hosting server level is queued here (from the {@code Entity.setPosRaw} hook) and
 * teleported to the hosting dimension at the same coordinates on the next hosting
 * container tick.
 *
 * <p>Exclusions mirror {@code relocatePlotEntities}: players, IP portals, removed
 * entities, and passengers (their vehicle migrates; vanilla re-seats).
 */
public final class IplPlotEntityMigration {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-plot-entity");

    /** Entities awaiting migration; weak so a discarded entity never leaks. */
    private static final Set<Entity> QUEUE =
        Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private IplPlotEntityMigration() {}

    /**
     * Called from the {@code setPosRaw} hook AFTER the coarse coordinate gate. Queues the
     * entity when its new position falls inside the hosting plot grid while it lives in a
     * non-hosting server level.
     */
    public static void onPlotRangePosition(Entity entity, double x, double z) {
        Level level = entity.level();
        if (level == null || level.isClientSide()) return;
        if (IplDimAgnostic.isHostingLevel(level)) return;
        if (entity.isRemoved()) return;
        if (entity instanceof ServerPlayer || entity instanceof Portal) return;

        SubLevelContainer hosting = IplDimAgnostic.getHostingContainerFor(level);
        if (hosting == null) return;
        if (!hosting.inBounds(SectionPos.blockToSectionCoord((int) Math.floor(x)),
                              SectionPos.blockToSectionCoord((int) Math.floor(z)))) {
            return; // beyond the coarse gate but not actually in the plot grid
        }

        QUEUE.add(entity);
    }

    /** Drain the queue; called once per hosting-container tick from {@code SableRehomeOps.sweep}. */
    public static void drain(ServerLevel hosting) {
        if (QUEUE.isEmpty()) return;

        ArrayDeque<Entity> pending;
        synchronized (QUEUE) {
            pending = new ArrayDeque<>(QUEUE);
            QUEUE.clear();
        }

        for (Entity entity : pending) {
            try {
                if (entity.isRemoved()) continue;
                Level level = entity.level();
                if (!(level instanceof ServerLevel from) || IplDimAgnostic.isHostingLevel(from)) {
                    continue; // already migrated (e.g. rehome relocation beat us to it)
                }
                if (entity.getVehicle() != null) continue;
                SubLevelContainer hostingContainer = IplDimAgnostic.getHostingContainerFor(from);
                if (hostingContainer == null) continue;
                if (!hostingContainer.inBounds(
                    SectionPos.blockToSectionCoord(entity.getBlockX()),
                    SectionPos.blockToSectionCoord(entity.getBlockZ()))) {
                    continue; // moved back out of plot space before the drain
                }

                var delta = entity.getDeltaMovement();
                Entity result = ServerTeleportationManager.teleportEntityGeneral(
                    entity, entity.position(), hosting);
                if (result != null && !result.isRemoved()) {
                    result.setDeltaMovement(delta);
                }
                LOG.info("[IPL-PLOT-ENTITY] migrated {} into {} at ({}, {}, {})",
                    entity.getType(), hosting.dimension().location(),
                    (int) entity.getX(), (int) entity.getY(), (int) entity.getZ());
            } catch (Throwable t) {
                LOG.warn("[IPL-PLOT-ENTITY] failed to migrate {}", entity, t);
            }
        }
    }
}
