package ipl.sable.transit;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Entities living in a body's plot (contraptions, seats, frames) when the body moves to another
 * dimension: they go to the same plot-local place in the successor's plot, with the non-player
 * entities riding them. A controlled contraption keeps its controller (the bearing keeps turning
 * it) even when the successor got another slot. Clients receive them in the destination level
 * with their spawn data ({@code MixinClientPayloadHandler}).
 */
final class IplPlotEntityTransit {
    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-flip");

    private IplPlotEntityTransit() {}

    /**
     * Moves the entities of {@code source}'s plot in {@code from} into {@code target}'s plot in
     * {@code to}. Sable's box queries also return world entities around the body and entities of
     * other plots: only entities of this plot move.
     */
    static void moveAll(ServerSubLevel source, ServerSubLevel target, ServerLevel from, ServerLevel to) {
        ServerLevelPlot srcPlot = source.getPlot();
        ServerLevelPlot dstPlot = target.getPlot();
        for (PlotChunkHolder holder : new ArrayList<>(srcPlot.getLoadedChunks())) {
            ChunkPos srcChunk = holder.getChunk().getPos();
            ChunkPos dstChunk = dstPlot.toGlobal(srcPlot.toLocal(srcChunk));
            int dx = dstChunk.getMinBlockX() - srcChunk.getMinBlockX();
            int dz = dstChunk.getMinBlockZ() - srcChunk.getMinBlockZ();
            AABB box = new AABB(srcChunk.getMinBlockX(), from.getMinBuildHeight(), srcChunk.getMinBlockZ(),
                srcChunk.getMinBlockX() + 16.0, from.getMaxBuildHeight(), srcChunk.getMinBlockZ() + 16.0);
            for (Entity entity : from.getEntitiesOfClass(Entity.class, box)) {
                if (entity instanceof Portal || entity instanceof ServerPlayer || entity.isRemoved()
                    || entity.getVehicle() != null || Sable.HELPER.getContaining(entity) != source) continue;
                move(entity, dx, dz, to, source);
            }
        }
    }

    /** Moves {@code entity} and the non-player entities riding it; returns its successor, or null. */
    @Nullable
    private static Entity move(Entity entity, int dx, int dz, ServerLevel to, ServerSubLevel source) {
        List<Entity> riders = new ArrayList<>();
        for (Entity rider : entity.getPassengers()) if (!(rider instanceof ServerPlayer)) riders.add(rider);
        Entity moved;
        try {
            moved = ServerTeleportationManager.teleportEntityGeneral(entity, entity.position().add(dx, 0.0, dz), to);
        } catch (RuntimeException e) {
            LOG.warn("[IPL-MOVE] failed to relocate plot entity {} of {}", entity, source.getUniqueId(), e);
            return null;
        }
        if (moved == null || moved.isRemoved()) return null;
        moved.setDeltaMovement(entity.getDeltaMovement());
        boolean driven = IplContraptions.followPlot(moved, dx, dz);
        if (IplContraptions.isContraption(moved)) {
            LOG.info("[IPL-MOVE] contraption #{} of {} -> {} at {}, controller {}", moved.getId(),
                source.getUniqueId(), to.dimension().location(), moved.blockPosition(), driven ? "attached" : "missing");
        }
        for (Entity rider : riders) {
            Entity movedRider = move(rider, dx, dz, to, source);
            if (movedRider != null) movedRider.startRiding(moved, true);
        }
        return moved;
    }
}
