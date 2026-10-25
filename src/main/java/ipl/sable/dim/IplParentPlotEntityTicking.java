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
 * entities to hold plot-local coordinates. Kinematic actors remain in their physical parent level so
 * their world-keyed addon state remains valid; retained plot volumes live in storage. The parent entity manager must
 * still tick its exact plot chunk. This class mirrors only entity-ticking status; block/chunk
 * reads are served separately by the parent plot cache bridge.
 */
public final class IplParentPlotEntityTicking {

    private record Membership(ServerLevel level, ChunkPos chunk) {}

    private static final Logger LOG = LoggerFactory.getLogger("ipl-plot-entity-ticking");

    private static final Map<Entity, Membership> MEMBERSHIPS =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Membership, Integer> ACTIVE_COUNTS = new HashMap<>();
    private static final java.util.Set<Membership> PLOT_SECTIONS = new java.util.HashSet<>();

    /** Load saved parent-owned actors even before their first move/join callback. Only
     * entity sections are made ticking; no parent block chunks are manufactured. */
    public static synchronized void reconcilePlots(net.minecraft.server.MinecraftServer server) {
        var next = new java.util.HashSet<Membership>();
        var storage = SableSubLevelDimension.getSableSubLevelsOrNull(server);
        if (storage != null) {
            var container = SubLevelContainer.getContainer(storage);
            if (container != null) for (var sub : IplOwnedSubLevels.owned(container)) {
                if (sub.isRemoved()) continue;
                var parent = IplDimAgnostic.getServerParentLevel(sub);
                if (parent == null) continue;
                for (var holder : sub.getPlot().getLoadedChunks()) next.add(new Membership(parent,holder.getPos()));
            }
        }
        var old = new java.util.HashSet<>(PLOT_SECTIONS);
        PLOT_SECTIONS.clear(); PLOT_SECTIONS.addAll(next);
        for (var entry : next) if (!old.contains(entry))
            ((IplServerEntityManagerAccessor)entry.level).ipl$entityManager()
                .updateChunkStatus(entry.chunk,FullChunkStatus.ENTITY_TICKING);
        for (var entry : old) if (!next.contains(entry) && !ACTIVE_COUNTS.containsKey(entry))
            ((IplServerEntityManagerAccessor)entry.level).ipl$entityManager()
                .updateChunkStatus(entry.chunk,FullChunkStatus.INACCESSIBLE);
    }
    public static synchronized void clear() { MEMBERSHIPS.clear();ACTIVE_COUNTS.clear();PLOT_SECTIONS.clear(); }


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
    public static synchronized boolean protects(Object manager, ChunkPos chunk) {
        for (Membership membership : java.util.stream.Stream.concat(ACTIVE_COUNTS.keySet().stream(),PLOT_SECTIONS.stream()).distinct().toList()) {
            if (membership.chunk.equals(chunk)
                && membership.level.getServer().isRunning()
                && ((IplServerEntityManagerAccessor)membership.level).ipl$entityManager()==manager) return true;
        }
        return false;
    }

    public static void update(Entity entity, double x, double z) {
        Level entityLevel = entity.level();
        // Vanilla Entity.equals/hashCode compare by entity id, so in singleplayer the
        // CLIENT copy aliases the server entity's map entry — Sable's client move-packet
        // lerp was "releasing" server memberships through that alias. Client entities have
        // no business here at all.
        if (entityLevel.isClientSide) return;
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
            return;
        }
        if (entityLevel instanceof ServerLevel sl
            && Thread.currentThread() != sl.getServer().getRunningThread()) {
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
        if (previous != null) release(previous);
        if (next == null) {
            MEMBERSHIPS.remove(entity);
            return;
        }

        MEMBERSHIPS.put(entity, next);
        retain(next);
    }

    public static void remove(Entity entity) {
        if (entity.level().isClientSide) return; // id-aliased client copy, see update()
        Membership held = MEMBERSHIPS.get(entity);
        if (held != null
            && Thread.currentThread() != held.level.getServer().getRunningThread()) {
            return; // real server removals arrive on the server thread
        }
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
        if (PLOT_SECTIONS.contains(membership)) return;
        // A live hosted plot owns this coordinate, so no ordinary parent terrain chunk should
        // share it. The next parent-owned plot entity re-arms it through setPosRaw.
        ((IplServerEntityManagerAccessor) membership.level).ipl$entityManager()
            .updateChunkStatus(membership.chunk, FullChunkStatus.INACCESSIBLE);
    }
}
