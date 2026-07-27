package ipl.sable.dim;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import ipl.sable.mixin.IplServerEntityManagerAccessor;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Atlas stores plot chunks in the hosting level, while stock Sable permits ordinary parent
 * entities to hold plot-local coordinates. Such an entity must remain in its parent level so
 * arbitrary addon state keyed by that level remains valid, but the parent entity manager must
 * still tick its exact plot chunk. This class mirrors only entity-ticking status; block/chunk
 * reads are served separately by the parent plot cache bridge.
 */
public final class IplParentPlotEntityTicking {

    private record Membership(ServerLevel level, ChunkPos chunk) {}

    private static final Map<Entity, Membership> MEMBERSHIPS =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Membership, Integer> ACTIVE_COUNTS = new HashMap<>();

    private IplParentPlotEntityTicking() {}

    public static void update(Entity entity, double x, double z) {
        Level entityLevel = entity.level();
        Membership next = null;
        if (entityLevel instanceof ServerLevel level && !IplDimAgnostic.isHostingLevel(level)
            && !entity.isRemoved()) {
            SubLevelContainer hosting = IplDimAgnostic.getHostingContainerFor(level);
            int chunkX = ((int) Math.floor(x)) >> 4;
            int chunkZ = ((int) Math.floor(z)) >> 4;
            if (hosting != null && hosting.inBounds(chunkX, chunkZ)) {
                LevelPlot plot = hosting.getPlot(chunkX, chunkZ);
                SubLevel sub = plot == null ? null : plot.getSubLevel();
                if (sub != null && !sub.isRemoved()
                    && IplDimAgnostic.getServerParentLevel(sub) == level) {
                    next = new Membership(level, new ChunkPos(chunkX, chunkZ));
                }
            }
        }

        Membership previous = MEMBERSHIPS.get(entity);
        if (next != null && next.equals(previous)) return;
        if (previous != null) release(previous);
        if (next == null) {
            MEMBERSHIPS.remove(entity);
            return;
        }

        MEMBERSHIPS.put(entity, next);
        retain(next);
    }

    public static void remove(Entity entity) {
        Membership previous = MEMBERSHIPS.remove(entity);
        if (previous != null) release(previous);
    }

    private static synchronized void retain(Membership membership) {
        int count = ACTIVE_COUNTS.getOrDefault(membership, 0);
        ACTIVE_COUNTS.put(membership, count + 1);
        if (count == 0) {
            ((IplServerEntityManagerAccessor) membership.level).ipl$entityManager()
                .updateChunkStatus(membership.chunk, FullChunkStatus.ENTITY_TICKING);
        }
    }

    private static synchronized void release(Membership membership) {
        int count = ACTIVE_COUNTS.getOrDefault(membership, 0);
        if (count > 1) {
            ACTIVE_COUNTS.put(membership, count - 1);
            return;
        }
        ACTIVE_COUNTS.remove(membership);
        // A live hosted plot owns this coordinate, so no ordinary parent terrain chunk should
        // share it. The next parent-owned plot entity re-arms it through setPosRaw.
        ((IplServerEntityManagerAccessor) membership.level).ipl$entityManager()
            .updateChunkStatus(membership.chunk, FullChunkStatus.INACCESSIBLE);
    }
}
