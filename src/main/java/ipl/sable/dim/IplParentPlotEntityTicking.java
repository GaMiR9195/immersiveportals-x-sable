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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger("ipl-plot-entity-ticking");

    private static final Map<Entity, Membership> MEMBERSHIPS =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Membership, Integer> ACTIVE_COUNTS = new HashMap<>();

    private IplParentPlotEntityTicking() {}

    /** Whether this entity currently holds a plot-chunk ticking membership — i.e. it is a
     * parent-level entity deliberately parked at hosted plot coordinates. */
    public static boolean isPlotEntity(Entity entity) {
        return MEMBERSHIPS.containsKey(entity);
    }

    /**
     * An external caller just downgraded entity-chunk visibility for {@code chunk} on
     * {@code manager}. If we still hold active memberships there (live plot entities),
     * restore ENTITY_TICKING — otherwise the entities silently stop ticking and tracking
     * (the client sees them disappear) even when the unload guard keeps them alive.
     */
    public static synchronized boolean reassertIfActive(Object manager, ChunkPos chunk) {
        for (Membership membership : ACTIVE_COUNTS.keySet()) {
            if (!membership.chunk.equals(chunk)) continue;
            if (((IplServerEntityManagerAccessor) membership.level).ipl$entityManager()
                != manager) {
                continue;
            }
            if (membership.level.getServer().isStopped()) return false;
            LOG.warn("[IPL-PLOT-TICKING] re-asserting ENTITY_TICKING on chunk={} after "
                + "external downgrade ({} active plot entities)",
                chunk, ACTIVE_COUNTS.get(membership));
            ((IplServerEntityManagerAccessor) membership.level).ipl$entityManager()
                .updateChunkStatus(chunk, FullChunkStatus.ENTITY_TICKING);
            return true;
        }
        return false;
    }

    public static void update(Entity entity, double x, double z) {
        Level entityLevel = entity.level();
        // Off-thread setPosRaw on server entities happens in singleplayer (render-side code
        // reaching into the integrated server). Membership evaluation fails contextually
        // there — the hosting-container lookup doesn't resolve — and acting on that false
        // "left the plot" verdict released the membership, flipped the plot chunk
        // INACCESSIBLE, and let vanilla unload the entity mid-use. Bookkeeping that
        // mutates server entity-manager state runs on the server thread only. The check
        // anchors on the MEMBERSHIP's captured level: entity.level() proved unreliable as
        // a thread discriminator in the wild (releases kept slipping through a guard keyed
        // on it), while the membership level is by construction the parent ServerLevel.
        Membership held = MEMBERSHIPS.get(entity);
        if (held != null
            && Thread.currentThread() != held.level.getServer().getRunningThread()) {
            logOffThreadTouch(entity, x, z);
            return;
        }
        if (entityLevel instanceof ServerLevel sl
            && Thread.currentThread() != sl.getServer().getRunningThread()) {
            logOffThreadTouch(entity, x, z);
            return;
        }
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
        if (previous != null) {
            LOG.info("[IPL-PLOT-TICKING] release chunk={} by entity={} id={} moved to ({}, {})"
                + " next={}", previous.chunk, entity.getType().getDescriptionId(),
                entity.getId(), String.format("%.1f", x), String.format("%.1f", z),
                next == null ? "none" : next.chunk);
            release(previous);
        }
        if (next == null) {
            MEMBERSHIPS.remove(entity);
            return;
        }

        MEMBERSHIPS.put(entity, next);
        LOG.info("[IPL-PLOT-TICKING] retain chunk={} by entity={} id={}",
            next.chunk, entity.getType().getDescriptionId(), entity.getId());
        retain(next);
    }

    private static long lastOffThreadLogMs;

    /** DIAGNOSTIC: name the render-side caller that repositions server entities, for the
     * record — the thread guard makes it harmless either way. */
    private static void logOffThreadTouch(Entity entity, double x, double z) {
        long now = System.currentTimeMillis();
        synchronized (IplParentPlotEntityTicking.class) {
            if (now - lastOffThreadLogMs < 2000) return;
            lastOffThreadLogMs = now;
        }
        StringBuilder stack = new StringBuilder();
        StackTraceElement[] frames = new Throwable().getStackTrace();
        for (int i = 2; i < Math.min(frames.length, 9); i++) {
            stack.append("\n    at ").append(frames[i]);
        }
        Level level = entity.level();
        LOG.info("[IPL-PLOT-TICKING] off-thread setPosRaw on entity={} id={} "
            + "to ({}, {}) on thread={} levelClass={} clientSide={} sameThread={} — skipped{}",
            entity.getType().getDescriptionId(), entity.getId(),
            String.format("%.1f", x), String.format("%.1f", z),
            Thread.currentThread().getName(), level.getClass().getSimpleName(),
            level.isClientSide,
            level instanceof ServerLevel sl && sl.getServer().isSameThread(), stack);
    }

    public static void remove(Entity entity) {
        Membership held = MEMBERSHIPS.get(entity);
        if (held != null
            && Thread.currentThread() != held.level.getServer().getRunningThread()) {
            return; // real server removals arrive on the server thread
        }
        Membership previous = MEMBERSHIPS.remove(entity);
        if (previous != null) {
            LOG.info("[IPL-PLOT-TICKING] release chunk={} by removed entity={} id={}",
                previous.chunk, entity.getType().getDescriptionId(), entity.getId());
            release(previous);
        }
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
