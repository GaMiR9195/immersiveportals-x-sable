package ipl.sable.client;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.ryanhcode.sable.network.client.ClientSableInterpolationState;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.mixin.client.InterpolationClockAccessor;
import ipl.sable.transit.IplStraddleSessionSync;
import ipl.sable.transit.IplPieceMap;
import ipl.sable.transit.IplProjectionTree;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.ducks.IEWorld;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Client mirror of the server's straddle sessions and piece labels ({@code
 * IplStraddleSessionSync}): per body, the session list (path, portal geometry, piece id) and
 * the {@link IplPieceMap} that says which material belongs to which session.
 *
 * <p>Both are state of one coordinate frame. Payloads of a frame the client has not entered
 * yet (a flip whose delayed render pose has not reached the portal) wait in {@link #FUTURE}
 * and replace the old frame's state at {@link #enterFrame}; until then the old frame keeps
 * drawing unchanged, so no "ended session" tail is ever needed.
 *
 * <p>Portals resolve to the live entity when IP synced it, otherwise to a detached surrogate
 * built from the geometry in the snapshot (IP only syncs portals relevant to the camera).
 */
public final class IplStraddleSessionStore {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-straddle-session-store");
    private static final IplPieceMap EMPTY_PIECES = new IplPieceMap();

    /** {@code path} = {@code root} or {@code root>child>...} of portal ids; {@code via} = parent path. */
    private record SessionPortal(UUID portalId, String portalNbtB64, String path, String via, int node) {}

    /** One resolved session: its piece, its parent's piece and the portals of its path. */
    public record ResolvedSession(int node, int parent, List<Portal> path) {
        public Portal portal() {
            return path.get(path.size() - 1);
        }
    }

    /**
     * What one client object of a body draws (README, section 8): its labels (plot shift in
     * blocks for a transit ghost, whose plot sits elsewhere than its successor's), the piece
     * drawn at its own pose ({@code -1} = none) and every session image with its piece.
     */
    public record RenderView(IplPieceMap pieces, int shiftX, int shiftZ, int nativeNode,
                             List<ResolvedSession> sessions) {
        public int label(int x, int y, int z) {
            return pieces.label(x + shiftX, y, z + shiftZ);
        }

        @Nullable
        public ResolvedSession session(int node) {
            for (ResolvedSession s : sessions) if (s.node() == node) return s;
            return null;
        }
    }

    private static final RenderView NO_VIEW = new RenderView(EMPTY_PIECES, 0, 0, IplPieceMap.NATIVE, List.of());

    /** Payloads of a frame the client has not entered yet. */
    private static final class DeferredFrame {
        @Nullable List<SessionPortal> sessions;
        final List<CompoundTag> labels = new ArrayList<>(2);
    }

    /** The old frame of a cross-dimension move, drawn by the ghost until its successor reports. */
    private record GhostView(List<SessionPortal> sessions, IplPieceMap pieces) {}

    private static final Map<UUID, NavigableMap<Long, DeferredFrame>> FUTURE = new HashMap<>();
    private static final ConcurrentMap<UUID, List<SessionPortal>> SESSIONS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, IplPieceMap> PIECES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, GhostView> GHOST_VIEWS = new ConcurrentHashMap<>();

    private static final ConcurrentMap<UUID, Portal> SURROGATES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, String> SURROGATE_PAYLOADS = new ConcurrentHashMap<>();
    private record SurrogateMiss(String payload, ClientLevel level) {}
    private static final ConcurrentMap<UUID, SurrogateMiss> SURROGATE_MISSES = new ConcurrentHashMap<>();

    /** Per-tick cache of resolved views (render thread). */
    private static final Map<ClientSubLevel, RenderView> VIEWS = new IdentityHashMap<>();
    private static long viewsStamp = Long.MIN_VALUE;
    private static long viewsGeneration = Long.MIN_VALUE;

    private IplStraddleSessionStore() {}

    // ------------------------------------------------------------------ queries

    public static boolean hasSession(UUID shipId) {
        return SESSIONS.containsKey(shipId);
    }

    /** True when no body has a session or a ghost view (cheap hot-path gate). */
    public static boolean isIdle() {
        return SESSIONS.isEmpty() && GHOST_VIEWS.isEmpty();
    }

    /** Whether {@code shipId} may currently have any portal image. */
    public static boolean mayStraddle(UUID shipId) {
        return SESSIONS.containsKey(shipId) || GHOST_VIEWS.containsKey(shipId);
    }

    /** The mirrored labels of a body (current frame; empty when unknown). */
    public static IplPieceMap pieces(UUID shipId) {
        IplPieceMap map = PIECES.get(shipId);
        return map != null ? map : EMPTY_PIECES;
    }

    /**
     * The authoritative sessions of a body's own client object, parents before children;
     * transit ghosts have none (their successor owns interaction).
     */
    public static List<ResolvedSession> sessions(ClientSubLevel sub) {
        if (sub == null || IplParentDimSync.isGhost(sub)) return List.of();
        List<SessionPortal> list = SESSIONS.get(sub.getUniqueId());
        return list == null ? List.of() : resolve(list, sub.getLevel());
    }

    /** Session entries for the projection tree (same sessions as {@link #sessions}). */
    public static List<IplProjectionTree.Entry> treeEntries(ClientSubLevel sub) {
        List<SessionPortal> list = sub == null || IplParentDimSync.isGhost(sub) ? null : SESSIONS.get(sub.getUniqueId());
        if (list == null) return List.of();
        List<ResolvedSession> resolved = resolve(list, sub.getLevel());
        List<IplProjectionTree.Entry> out = new ArrayList<>(resolved.size());
        Map<Integer, SessionPortal> byNode = new HashMap<>();
        for (SessionPortal p : list) byNode.put(p.node(), p);
        for (ResolvedSession s : resolved) {
            SessionPortal p = byNode.get(s.node());
            if (p != null) out.add(new IplProjectionTree.Entry(p.path(), p.via(), s.portal(), s.node()));
        }
        return out;
    }

    /** What {@code sub} draws this frame (see {@link RenderView}); cached per tick. */
    public static RenderView renderView(ClientSubLevel sub) {
        if (sub == null || isIdle()) return NO_VIEW;
        long stamp = IplClientProjections.clientStamp();
        long generation = IplProjectionTree.generation();
        if (stamp != viewsStamp || generation != viewsGeneration) {
            VIEWS.clear();
            viewsStamp = stamp;
            viewsGeneration = generation;
        }
        RenderView view = VIEWS.get(sub);
        if (view == null) {
            view = computeView(sub);
            VIEWS.put(sub, view);
        }
        return view;
    }

    private static RenderView computeView(ClientSubLevel sub) {
        UUID id = sub.getUniqueId();
        if (!IplParentDimSync.isGhost(sub)) {
            List<SessionPortal> list = SESSIONS.get(id);
            if (list == null) return NO_VIEW;
            return new RenderView(pieces(id), 0, 0, IplPieceMap.NATIVE, resolve(list, sub.getLevel()));
        }
        ClientSubLevel successor = IplParentDimSync.ghostSuccessor(sub);
        if (successor != null) return ghostView(sub, successor);
        // Until the successor is tracked the ghost keeps its own (old) frame, poses included.
        GhostView old = GHOST_VIEWS.get(id);
        return old == null ? NO_VIEW
            : new RenderView(old.pieces(), 0, 0, IplPieceMap.NATIVE, resolve(old.sessions(), sub.getLevel()));
    }

    /**
     * A ghost draws its successor's pieces re-rooted at the crossing: the successor session
     * that leads back through the crossing portal is the ghost's native piece, the successor's
     * native piece is the image through the crossing, every other session hangs below one of
     * the two.
     */
    private static RenderView ghostView(ClientSubLevel ghost, ClientSubLevel successor) {
        Portal crossing = IplParentDimSync.ghostPortalFor(ghost);
        if (crossing == null) return NO_VIEW;
        List<SessionPortal> list = SESSIONS.get(ghost.getUniqueId());
        List<ResolvedSession> theirs = list == null ? List.of() : resolve(list, successor.getLevel());
        ResolvedSession back = null;
        for (ResolvedSession s : theirs) {
            if (s.path().size() == 1 && leadsBack(crossing, s.portal())) { back = s; break; }
        }
        List<ResolvedSession> out = new ArrayList<>(theirs.size() + 1);
        int nativeNode = back != null ? back.node() : -1;
        out.add(new ResolvedSession(IplPieceMap.NATIVE, nativeNode, List.of(crossing)));
        for (ResolvedSession s : theirs) {
            if (s == back) continue;
            if (back != null && s.path().get(0) == back.portal()) {
                out.add(new ResolvedSession(s.node(), s.parent(), List.copyOf(s.path().subList(1, s.path().size()))));
            } else {
                List<Portal> path = new ArrayList<>(s.path().size() + 1);
                path.add(crossing);
                path.addAll(s.path());
                out.add(new ResolvedSession(s.node(), s.parent(), List.copyOf(path)));
            }
        }
        int[] shift = plotShift(ghost, successor);
        return new RenderView(pieces(ghost.getUniqueId()), shift[0], shift[1], nativeNode, List.copyOf(out));
    }

    private static int[] plotShift(ClientSubLevel ghost, ClientSubLevel successor) {
        ChunkPos g = ghost.getPlot().getChunkMin();
        ChunkPos s = successor.getPlot().getChunkMin();
        return new int[]{(s.x - g.x) * 16, (s.z - g.z) * 16};
    }

    /** The reverse of {@code crossed}: its exit rectangle, entered from the other side. */
    private static boolean leadsBack(Portal crossed, Portal candidate) {
        return candidate.getDestDim() == crossed.level().dimension()
            && candidate.getOriginPos().distanceToSqr(crossed.getDestPos()) <= 1.0e-4
            && candidate.getDestPos().distanceToSqr(crossed.getOriginPos()) <= 1.0e-4;
    }

    /** The live portal entity {@code portalId} in {@code level}, or null. */
    @Nullable
    public static Portal findLivePortal(ClientLevel level, UUID portalId) {
        Portal portal = lookupPortal(level, portalId);
        return portal != null && !portal.isRemoved() ? portal : null;
    }

    /**
     * One portal by id: global portals first (last wins, as in IP's map), then IP's O(1) entity
     * index; never a scan of every loaded entity (this runs inside Sable's spatial queries).
     */
    @Nullable
    public static Portal lookupPortal(ClientLevel level, UUID portalId) {
        Portal global = null;
        for (Portal portal : GlobalPortalStorage.getGlobalPortals(level)) {
            if (portal.getUUID().equals(portalId)) global = portal;
        }
        if (global != null) return global;
        if (!(level instanceof IEWorld world)) {
            for (Entity entity : level.entitiesForRendering()) {
                if (entity instanceof Portal portal && portal.getUUID().equals(portalId)) return portal;
            }
            return null;
        }
        return world.portal_getEntityLookup().get(portalId) instanceof Portal portal ? portal : null;
    }

    /**
     * Portal geometry carried by a flip RPC: the server may finish a crossing before IP tracks
     * the portal for this viewer, so a detached copy is built when the entity is missing.
     */
    @Nullable
    public static Portal resolveHandoffPortal(UUID portalId, String portalNbtB64, ClientLevel sourceLevel) {
        Portal live = findLivePortal(sourceLevel, portalId);
        return live != null ? live : surrogate(portalId, portalNbtB64, sourceLevel);
    }

    /** Drop surrogates no session references any more (after a flip committed). */
    public static void releaseHandoffPortal() {
        discardUnreferencedSurrogates();
    }

    // ------------------------------------------------------------------ lifecycle

    /** Discards everything known about a body whose client object is gone. */
    public static void clearAllVisualState(UUID shipId) {
        SESSIONS.remove(shipId);
        PIECES.remove(shipId);
        FUTURE.remove(shipId);
        GHOST_VIEWS.remove(shipId);
        TIMED.removeIf(t -> t.ship().equals(shipId));
        changed();
    }

    public static void clear() {
        SESSIONS.clear();
        PIECES.clear();
        FUTURE.clear();
        GHOST_VIEWS.clear();
        TIMED.clear();
        SURROGATES.clear();
        SURROGATE_PAYLOADS.clear();
        SURROGATE_MISSES.clear();
        VIEWS.clear();
        IplStraddleRenderCache.invalidateActivePasses();
        IplProjectionTree.invalidate();
    }

    /** A cross-dimension move made {@code shipId}'s old object a ghost: keep its frame for it. */
    public static void captureGhostView(UUID shipId) {
        List<SessionPortal> sessions = SESSIONS.get(shipId);
        IplPieceMap pieces = PIECES.get(shipId);
        if (sessions == null || pieces == null) return;
        IplPieceMap copy = new IplPieceMap();
        copy.decode(pieces.encode(null));
        GHOST_VIEWS.put(shipId, new GhostView(sessions, copy));
    }

    public static void clearGhostView(UUID shipId) {
        if (GHOST_VIEWS.remove(shipId) != null) changed();
    }

    /** Called only after all poses have been rebased into this exact frame. */
    public static void enterFrame(UUID id, long frame) {
        // Timed payloads of the old frame are replaced by the new frame's state.
        TIMED.removeIf(t -> t.ship().equals(id));
        SESSIONS.remove(id);
        PIECES.remove(id);
        var future = FUTURE.get(id);
        if (future != null) {
            DeferredFrame deferred = future.get(frame);
            future.headMap(frame, true).clear();
            if (future.isEmpty()) FUTURE.remove(id);
            if (deferred != null) {
                for (CompoundTag labels : deferred.labels) applyLabels(id, labels);
                if (deferred.sessions != null) applySessions(id, deferred.sessions);
            }
        }
        discardUnreferencedSurrogates();
        changed();
    }

    private static void changed() {
        VIEWS.clear();
        IplStraddleRenderCache.invalidateActivePasses();
        IplProjectionTree.invalidate();
    }

    // ------------------------------------------------------------------ resolution

    private static List<ResolvedSession> resolve(List<SessionPortal> list, ClientLevel nativeLevel) {
        List<ResolvedSession> out = new ArrayList<>(list.size());
        Map<String, ResolvedSession> byPath = new HashMap<>();
        // Server order already puts parents first; depth order makes it independent of that.
        List<SessionPortal> ordered = new ArrayList<>(list);
        ordered.sort((a, b) -> Integer.compare(depth(a.path()), depth(b.path())));
        for (SessionPortal entry : ordered) {
            ResolvedSession parent = entry.via().isEmpty() ? null : byPath.get(entry.via());
            if (!entry.via().isEmpty() && parent == null) continue;
            ClientLevel level = parent == null ? nativeLevel : IplClientProjections.clientLevel(parent.portal().getDestDim());
            if (level == null) continue;
            Portal portal = findLivePortal(level, entry.portalId());
            if (portal == null) portal = surrogate(entry.portalId(), entry.portalNbtB64(), level);
            if (portal == null || Math.abs(portal.getScaling() - 1.0) > 1e-9) continue;
            List<Portal> path;
            if (parent == null) {
                path = List.of(portal);
            } else {
                List<Portal> p = new ArrayList<>(parent.path().size() + 1);
                p.addAll(parent.path());
                p.add(portal);
                path = List.copyOf(p);
            }
            ResolvedSession resolved = new ResolvedSession(entry.node(), parent == null ? IplPieceMap.NATIVE : parent.node(), path);
            byPath.put(entry.path(), resolved);
            out.add(resolved);
        }
        return List.copyOf(out);
    }

    private static int depth(String path) {
        int depth = 1;
        for (int i = 0; i < path.length(); i++) if (path.charAt(i) == '>') depth++;
        return depth;
    }

    private static void discardUnreferencedSurrogates() {
        Set<UUID> referenced = new HashSet<>();
        for (List<SessionPortal> list : SESSIONS.values()) for (SessionPortal p : list) referenced.add(p.portalId());
        for (GhostView view : GHOST_VIEWS.values()) for (SessionPortal p : view.sessions()) referenced.add(p.portalId());
        SURROGATES.keySet().retainAll(referenced);
        SURROGATE_PAYLOADS.keySet().retainAll(referenced);
        SURROGATE_MISSES.keySet().retainAll(referenced);
    }

    /** Global-portal-style reconstruction: type + NBT -> detached Portal data carrier. */
    @Nullable
    private static Portal surrogate(UUID portalId, String payload, ClientLevel level) {
        Portal cached = SURROGATES.get(portalId);
        if (cached != null && cached.level() == level && payload.equals(SURROGATE_PAYLOADS.get(portalId))) return cached;
        if (payload.isEmpty()) return null;
        SurrogateMiss miss = SURROGATE_MISSES.get(portalId);
        if (miss != null && miss.level() == level && miss.payload().equals(payload)) return null;
        CompoundTag tag = decodeNbt(payload);
        // A future session can arrive before its queued flip: never rebuild it in the wrong level.
        if (tag.contains("ipl_source_dimension")
            && !tag.getString("ipl_source_dimension").equals(level.dimension().location().toString())) {
            return missSurrogate(portalId, payload, level);
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(tag.getString("entity_type")));
        Portal portal;
        try {
            if (!(type.create(level) instanceof Portal created)) return missSurrogate(portalId, payload, level);
            created.load(tag);
            created.setUUID(portalId);
            created.updateCache();
            portal = created;
        } catch (RuntimeException e) {
            LOG.error("[IPL-STRADDLE-SYNC] surrogate build failed for {}", portalId, e);
            return missSurrogate(portalId, payload, level);
        }
        SURROGATE_MISSES.remove(portalId);
        SURROGATES.put(portalId, portal);
        SURROGATE_PAYLOADS.put(portalId, payload);
        return portal;
    }

    @Nullable
    private static Portal missSurrogate(UUID portalId, String payload, ClientLevel level) {
        SURROGATE_MISSES.put(portalId, new SurrogateMiss(payload, level));
        return null;
    }

    /** Portal NBT as encoded by {@code IplStraddleSessionSync.encodePortal}. */
    private static CompoundTag decodeNbt(String base64) {
        String snbt = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
        try {
            return TagParser.parseTag(snbt);
        } catch (CommandSyntaxException e) {
            throw new IllegalArgumentException("malformed session portal NBT", e);
        }
    }

    // ------------------------------------------------------------------ payloads

    private static List<SessionPortal> parse(CompoundTag sessions) {
        ListTag list = sessions.getList("s", Tag.TAG_COMPOUND);
        List<SessionPortal> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            String path = e.getString("p");
            int cut = path.lastIndexOf('>');
            try {
                UUID portalId = UUID.fromString(cut < 0 ? path : path.substring(cut + 1));
                out.add(new SessionPortal(portalId, e.getString("g"), path, cut < 0 ? "" : path.substring(0, cut), e.getInt("n")));
            } catch (IllegalArgumentException malformed) {
                LOG.warn("[IPL-STRADDLE-SYNC] malformed session path {}", path);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Replaces a body's sessions. A session that vanished returns its material to its nearest
     * surviving ancestor, exactly as the server's tracker does before its label payload arrives.
     */
    private static void applySessions(UUID shipId, List<SessionPortal> next) {
        List<SessionPortal> previous = SESSIONS.get(shipId);
        IplPieceMap map = PIECES.get(shipId);
        if (previous != null && map != null) {
            int[] remap = new int[IplPieceMap.MAX_PIECE + 1];
            boolean any = false;
            Map<Integer, Integer> parentOf = new HashMap<>();
            Set<Integer> live = new HashSet<>();
            for (SessionPortal p : next) live.add(p.node());
            Map<String, Integer> nodeOfPath = new HashMap<>();
            for (SessionPortal p : previous) nodeOfPath.put(p.path(), p.node());
            for (SessionPortal p : previous) parentOf.put(p.node(), p.via().isEmpty() ? IplPieceMap.NATIVE : nodeOfPath.getOrDefault(p.via(), IplPieceMap.NATIVE));
            for (int i = 0; i <= IplPieceMap.MAX_PIECE; i++) {
                int target = i;
                int guard = 0;
                while (target != IplPieceMap.NATIVE && !live.contains(target) && parentOf.containsKey(target) && guard++ < 64) {
                    target = parentOf.get(target);
                }
                remap[i] = target;
                if (target != i) any = true;
            }
            if (any) map.relabel(l -> remap[l]);
        }
        if (next.isEmpty()) SESSIONS.remove(shipId);
        else SESSIONS.put(shipId, next);
        discardUnreferencedSurrogates();
        changed();
    }

    private static void applyLabels(UUID shipId, CompoundTag labels) {
        PIECES.computeIfAbsent(shipId, ignored -> new IplPieceMap()).decode(labels);
        changed();
    }

    // ------------------------------------------------------------------ timed payloads

    /**
     * A payload of the body's current frame stamped with the server tick it belongs to
     * ({@code IplStraddleSessionSync.TICK_KEY}). Sessions and labels describe the body at that
     * tick, but the client draws the body {@code getInterpolationDelay()} ticks in the past:
     * applied on arrival, a session that ended (a quick dip into a portal) removed the far
     * half of blocks that were still drawn in the doorway, and labels moved blocks to the
     * other side before they got there. They now wait until the shared interpolation clock
     * shows that tick. Payloads of a frame the client has not entered yet keep the frame
     * deferral (already pose-synced by {@link #enterFrame}).
     */
    private record Timed(UUID ship, CompoundTag payload, String frame, boolean labels, int tick) {}

    private static final ArrayDeque<Timed> TIMED = new ArrayDeque<>();

    private static boolean holdUntilShown(UUID shipId, CompoundTag payload, String coordinateFrame, boolean labels) {
        if (!payload.contains(IplStraddleSessionSync.TICK_KEY)) return false;
        long frame;
        try {
            frame = Long.parseLong(coordinateFrame);
        } catch (NumberFormatException e) {
            return false;
        }
        if (frame != IplParentDimSync.frame(shipId)) return false;
        int tick = payload.getInt(IplStraddleSessionSync.TICK_KEY);
        if (shown(tick) && TIMED.stream().noneMatch(t -> t.ship().equals(shipId))) return false;
        TIMED.addLast(new Timed(shipId, payload, coordinateFrame, labels, tick));
        return true;
    }

    private static boolean shown(int tick) {
        ClientSableInterpolationState clock = IplSharedClock.get();
        if (!((InterpolationClockAccessor) clock).ipl$getReceivedFirstUpdate() || clock.isStopped()) return true;
        return IplSessionTimeline.shown(tick, renderTime(clock));
    }

    /** Applies due payloads at render time, preserving order per body, not blocking other bodies. */
    public static void drainTimed() {
        IplSessionTimeline.drain(TIMED, Timed::ship, t -> shown(t.tick()), t -> {
            if (t.labels()) RemoteCallables.applyLabelPayload(t.ship(), t.payload(), t.frame());
            else RemoteCallables.applySnapshot(t.ship(), t.payload(), t.frame());
        });
        // Make only NEW authoritative paths available for the preceding render interval.
        // Applying due retirements first also permits safe piece-id reuse on the next tick.
        double pointer = renderTime(IplSharedClock.get());
        for (Timed t : TIMED) {
            if (!t.labels() && t.tick() - 1.0 <= pointer) previewAdditions(t);
        }
    }

    private static double renderTime(ClientSableInterpolationState clock) {
        float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
        return IplSessionTimeline.renderTime(clock.lastInterpolationTick, clock.mostRecentInterpolationTick, partial);
    }

    private static void previewAdditions(Timed timed) {
        long frame;
        try { frame = Long.parseLong(timed.frame()); } catch (NumberFormatException ignored) { return; }
        if (frame != IplParentDimSync.frame(timed.ship()) || IplClientProjections.findClientSub(timed.ship()) == null) return;
        List<SessionPortal> current = SESSIONS.getOrDefault(timed.ship(), List.of());
        List<SessionPortal> merged = new ArrayList<>(current);
        for (SessionPortal entry : parse(timed.payload())) {
            boolean known = false;
            for (SessionPortal old : merged) {
                if (old.path().equals(entry.path()) || old.node() == entry.node()) { known = true; break; }
            }
            if (!known) merged.add(entry);
        }
        if (merged.size() != current.size()) applySessions(timed.ship(), merged);
    }

    private static DeferredFrame deferred(UUID shipId, long frame) {
        return FUTURE.computeIfAbsent(shipId, ignored -> new TreeMap<>()).computeIfAbsent(frame, ignored -> new DeferredFrame());
    }

    public static final class RemoteCallables {

        /** Full session list of one body: {@code {s:[{p:path, g:geometry, n:piece}]}}. */
        public static void snapshot(String shipUuid, CompoundTag sessions, String coordinateFrame) {
            UUID shipId = UUID.fromString(shipUuid);
            if (holdUntilShown(shipId, sessions, coordinateFrame, false)) return;
            applySnapshot(shipId, sessions, coordinateFrame);
        }

        /** Label payload of one body ({@link IplPieceMap#encode}). */
        public static void labels(String shipUuid, CompoundTag labels, String coordinateFrame) {
            UUID shipId = UUID.fromString(shipUuid);
            if (holdUntilShown(shipId, labels, coordinateFrame, true)) return;
            applyLabelPayload(shipId, labels, coordinateFrame);
        }

        private static void applySnapshot(UUID shipId, CompoundTag sessions, String coordinateFrame) {
            // Broadcasts include untracked bodies; their track-start bootstrap sends the state.
            if (IplClientProjections.findClientSub(shipId) == null) return;
            long frame = Long.parseLong(coordinateFrame);
            long current = IplParentDimSync.frame(shipId);
            if (frame < current) return;
            List<SessionPortal> next = parse(sessions);
            if (frame > current) {
                deferred(shipId, frame).sessions = next;
                return;
            }
            applySessions(shipId, next);
        }

        private static void applyLabelPayload(UUID shipId, CompoundTag labels, String coordinateFrame) {
            if (IplClientProjections.findClientSub(shipId) == null) return;
            long frame = Long.parseLong(coordinateFrame);
            long current = IplParentDimSync.frame(shipId);
            if (frame < current) return;
            if (frame > current) {
                DeferredFrame deferred = deferred(shipId, frame);
                if (labels.getBoolean("full")) deferred.labels.clear();
                deferred.labels.add(labels);
                return;
            }
            applyLabels(shipId, labels);
        }
    }
}
