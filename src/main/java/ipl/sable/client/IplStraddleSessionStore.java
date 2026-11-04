package ipl.sable.client;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.transit.IplProjectionTree;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Client mirror of the server's straddle sessions (see
 * {@code IplStraddleSessionSync}): per ship, the portals of its active straddle
 * sessions, replaced wholesale by each snapshot RPC.
 *
 * <p>This store carries ONLY parity — which portal face the ship is crossing, hence
 * which half is "still here" — and each session's passage anchor, a plot-local point fixed for
 * the session's lifetime that slants its through prism exactly like the server's. Region
 * geometry is rebuilt every frame from the resolved portal's current transform and the
 * anchor's current position, so moving portals stay smooth while parity can never flicker:
 * it changes exactly when the server's latch does.
 *
 * <p>Portal resolution prefers the LIVE entity in the ship's parent level, but each
 * snapshot also carries the portal's full NBT (global-portal-style), from which a
 * detached surrogate is built when the entity isn't synced yet — a cross-dimension
 * reverse session names a portal from the other dimension, and IP only syncs portal
 * entities relevant to the camera; without the surrogate, the handoff had a multi-tick
 * hole with no projection and no collision image.
 */
public final class IplStraddleSessionStore {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-straddle-session-store");

    /**
     * {@code path} = chain path ({@code root} or {@code root>child>...}); roots use the portal id.
     * {@code anchor} = the session's plot-local passage anchor (the server's prism slope, see
     * {@code PortalPrism}); null when the server sent none.
     */
    private record SessionPortal(
        UUID portalId, String portalNbtB64, boolean completed, String path, @Nullable Vec3 anchor
    ) {
        SessionPortal(UUID id, String payload) { this(id, payload, false, id.toString(), null); }
        SessionPortal(UUID id, String payload, boolean completed) { this(id, payload, completed, id.toString(), null); }

        boolean isChain() { return path.indexOf('>') >= 0; }

        /**
         * The same session seen one portal earlier (a transit ghost renders through
         * {@code rootPath}). The anchor stays behind: it is local to the successor's plot, not
         * the ghost's, so the ghost's brief image uses the straight tube.
         */
        SessionPortal under(String rootPath) {
            return new SessionPortal(portalId, portalNbtB64, completed, rootPath + ">" + path, null);
        }
    }

    /** A session the server ended at source tracking tick {@code endTick}; never a wall-clock timeout. */
    private record Tail(SessionPortal entry, int endTick) {}

    /**
     * Snapshots of a coordinate frame the client has not entered yet (its parent flip is still
     * queued behind the delayed render stream).
     *
     * <p>В ряду порталов сервер успевает внутри ещё не применённого кадра открыть и закрыть
     * сессию (следующий флип наступает раньше, чем клиент догнал предыдущий), и последний
     * снимок кадра пуст. Одного последнего снимка мало: при входе в кадр кусок за следующим
     * порталом пропал бы целиком. Поэтому закончившиеся внутри кадра сессии хранятся хвостами
     * со своим тиком окончания и дорисовываются, пока до него не дошли часы интерполяции.
     */
    private static final class DeferredFrame {
        String payload = "";
        int endTick;
        final Map<String, Tail> ended = new LinkedHashMap<>();
    }
    /** Later chart's sessions cannot replace the delayed current chart's state. */
    private static final Map<UUID, NavigableMap<Long, DeferredFrame>> FUTURE = new HashMap<>();

    /** Ship → active ROOT session portals (session-start order; empty never stored). */
    private static final ConcurrentMap<UUID, List<SessionPortal>> SESSIONS = new ConcurrentHashMap<>();

    /** Ship → chained sessions (depth ≥ 2), each entry carrying its LAST portal. */
    private static final ConcurrentMap<UUID, List<SessionPortal>> CHAINS = new ConcurrentHashMap<>();

    /**
     * ROOT sessions the server has retired but the delayed render pose has not finished leaving.
     * Atlas physics removes its image at the authoritative exit tick; rendering must keep the
     * complementary clipped instances until the client catches up or a parent handoff remaps
     * the pose atomically.
     */
    private static final ConcurrentMap<UUID, List<Tail>> RETIRED = new ConcurrentHashMap<>();

    /**
     * The same render tail for CHAINED sessions.
     *
     * <p>Флип рассылает снимки конца всех сессий старого кадра, а новый кадр клиент применяет
     * только после того, как отложенная поза дошла до флипа (~1.5–2 тика). Если бы цепочки на
     * это время удалялись сразу, кусок за вторым порталом мигал бы, а корневая проекция теряла
     * бы плоскость входа в следующий портал и торчала за его гранью до применения флипа. Поэтому
     * хвост цепочки живёт по тому же правилу тика окончания, что и хвост корня.
     */
    private static final ConcurrentMap<UUID, List<Tail>> RETIRED_CHAINS = new ConcurrentHashMap<>();

    /**
     * Old-chart chains under the portal a cross-dimension move went through, kept for the
     * transit ghost until the successor's first snapshot (see {@link #captureGhostTails}).
     */
    private static final ConcurrentMap<UUID, List<Tail>> GHOST_TAILS = new ConcurrentHashMap<>();

    /** Detached surrogate portals built from snapshot NBT, by portal id. */
    private static final ConcurrentMap<UUID, Portal> SURROGATES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, String> SURROGATE_PAYLOADS = new ConcurrentHashMap<>();

    /**
     * Payloads that cannot be rebuilt in a level (another source dimension, a portal type that
     * fails to load). The answer is fixed per payload and level; without this, every spatial
     * query re-decoded and re-parsed the SNBT (and logged a failed load again).
     */
    private record SurrogateMiss(String payload, ClientLevel level) {}
    private static final ConcurrentMap<UUID, SurrogateMiss> SURROGATE_MISSES = new ConcurrentHashMap<>();

    /** Server flips without a full client straddle keep this portal as a render-only tail. */
    private static final ConcurrentMap<UUID, SessionPortal> HANDOFF_VISUALS = new ConcurrentHashMap<>();

    private IplStraddleSessionStore() {}

    /**
     * The authoritative straddle portal for this ship, or null when the server has no
     * session for it. Live entity in the parent level when synced; snapshot surrogate
     * otherwise.
     */
    @Nullable
    public static Portal resolvePortal(ClientSubLevel sub) {
        if (sub == null) return null;
        Portal ghost = IplParentDimSync.ghostPortalFor(sub);
        if (ghost != null) return ghost;
        ClientLevel level = sub.getLevel();
        List<SessionPortal> portals = SESSIONS.get(sub.getUniqueId());
        if (portals == null) return null;
        for (SessionPortal sessionPortal : portals) {
            Portal live = findPortal(level, sessionPortal.portalId());
            if (live != null) return live;
            Portal surrogate = surrogate(sessionPortal, level);
            if (surrogate != null) return surrogate;
        }
        return null;
    }

    public static boolean hasSession(UUID shipId) {
        return SESSIONS.containsKey(shipId);
    }

    /**
     * The plot-local passage anchor of session {@code path} (live first, then the render tails
     * the delayed pose still shows), or null: the prism slope must match the server's contact
     * clip, so interactions and the render cut use the same shape as physics.
     */
    @Nullable
    public static Vec3 anchor(UUID shipId, String path) {
        boolean chain = path.indexOf('>') >= 0;
        SessionPortal entry = byPath(chain ? CHAINS.get(shipId) : SESSIONS.get(shipId), path);
        if (entry == null) {
            Tail tail = tailByPath(chain ? RETIRED_CHAINS.get(shipId) : RETIRED.get(shipId), path);
            if (tail == null && chain) tail = tailByPath(GHOST_TAILS.get(shipId), path);
            if (tail != null) entry = tail.entry();
        }
        if (entry == null && !chain) {
            SessionPortal handoff = HANDOFF_VISUALS.get(shipId);
            if (handoff != null && handoff.path().equals(path)) entry = handoff;
        }
        return entry == null ? null : entry.anchor();
    }

    /** True when no session, chain, retired or handoff image exists (cheap hot-path gate). */
    public static boolean isIdle() {
        return SESSIONS.isEmpty() && CHAINS.isEmpty() && RETIRED.isEmpty() && RETIRED_CHAINS.isEmpty()
            && HANDOFF_VISUALS.isEmpty();
    }

    /** Whether {@code shipId} may currently have any portal image. */
    public static boolean mayStraddle(UUID shipId) {
        return SESSIONS.containsKey(shipId) || CHAINS.containsKey(shipId) || RETIRED.containsKey(shipId)
            || RETIRED_CHAINS.containsKey(shipId) || HANDOFF_VISUALS.containsKey(shipId);
    }

    /** The live portal entity {@code portalId} in {@code level}, or null. */
    @Nullable
    public static Portal findLivePortal(ClientLevel level, UUID portalId) {
        return findPortal(level, portalId);
    }

    /**
     * Resolves portal geometry carried by a parent-handoff RPC. The server may finish a
     * crossing before IP tracks the source portal for this viewer, so an exact detached
     * portal is required for the client AABB A→B proof.
     */
    @Nullable
    public static Portal resolveHandoffPortal(
        UUID portalId, String portalNbtB64, ClientLevel sourceLevel
    ) {
        Portal live = findPortal(sourceLevel, portalId);
        return live != null ? live : surrogate(new SessionPortal(portalId, portalNbtB64), sourceLevel);
    }

    /** Drop an unreferenced handoff surrogate after its pending frame switch commits. */
    public static void releaseHandoffPortal() {
        discardUnreferencedSurrogates();
    }

    /** Starts a handoff visual tail before client A→B confirmation. */
    public static void beginHandoffVisual(UUID shipId, UUID portalId, String portalNbtB64) {
        SessionPortal next = new SessionPortal(portalId, portalNbtB64, true);
        if (!next.equals(HANDOFF_VISUALS.put(shipId, next))) {
            IplStraddleRenderCache.invalidateActivePasses();
            IplProjectionTree.invalidate();
        }
    }

    /** Removes a completed or superseded handoff visual tail. */
    public static void clearHandoffVisual(UUID shipId) {
        if (HANDOFF_VISUALS.remove(shipId) != null) {
            discardUnreferencedSurrogates();
            IplStraddleRenderCache.invalidateActivePasses();
            IplProjectionTree.invalidate();
        }
    }

    /** Discards a tail whose client object never materialized. */
    public static void clearAllVisualState(UUID shipId) {
        HANDOFF_VISUALS.remove(shipId);
        SESSIONS.remove(shipId);
        CHAINS.remove(shipId);
        FUTURE.remove(shipId);
        RETIRED.remove(shipId);
        RETIRED_CHAINS.remove(shipId);
        GHOST_TAILS.remove(shipId);
        discardUnreferencedSurrogates();
        IplClientVisualTransitLatch.clear(shipId);
        IplStraddleRenderCache.invalidateActivePasses();
        IplProjectionTree.invalidate();
    }

    public static void clear() {
        Set<UUID> ids = new HashSet<>(SESSIONS.keySet());
        ids.addAll(RETIRED.keySet());
        ids.addAll(RETIRED_CHAINS.keySet());
        ids.addAll(HANDOFF_VISUALS.keySet());
        for (UUID id : ids) IplClientVisualTransitLatch.clear(id);
        SESSIONS.clear();
        CHAINS.clear();
        FUTURE.clear();
        RETIRED.clear();
        RETIRED_CHAINS.clear();
        GHOST_TAILS.clear();
        HANDOFF_VISUALS.clear();
        SURROGATES.clear();
        SURROGATE_PAYLOADS.clear();
        SURROGATE_MISSES.clear();
        IplClientVisualTransitLatch.clearAll();
        IplStraddleRenderCache.invalidateActivePasses();
        IplProjectionTree.invalidate();
    }

    /** Called only after all poses have been rebased into this exact chart. */
    public static void enterFrame(UUID id, long frame) {
        SESSIONS.remove(id);
        CHAINS.remove(id);
        RETIRED.remove(id);
        RETIRED_CHAINS.remove(id);
        HANDOFF_VISUALS.remove(id);
        var future = FUTURE.get(id);
        if (future != null) {
            DeferredFrame deferred = future.get(frame);
            future.headMap(frame, true).clear();
            if (future.isEmpty()) FUTURE.remove(id);
            if (deferred != null) {
                applySnapshot(id, deferred.payload, deferred.endTick);
                // Sessions that began and ended inside this frame before it was entered.
                for (Tail tail : deferred.ended.values()) {
                    boolean chain = tail.entry().isChain();
                    if (byPath(chain ? CHAINS.get(id) : SESSIONS.get(id), tail.entry().path()) != null) continue;
                    (chain ? RETIRED_CHAINS : RETIRED).merge(id, List.of(tail), IplStraddleSessionStore::concat);
                }
            }
        }
        discardUnreferencedSurrogates();
        IplStraddleRenderCache.invalidateActivePasses();
        IplProjectionTree.invalidate();
    }

    /** ALL resolvable session portals for this ship, session-start order (multi-straddle). */
    public static List<Portal> resolveAllPortals(ClientSubLevel sub) {
        if (sub == null) return List.of();
        Portal ghost = IplParentDimSync.ghostPortalFor(sub);
        if (ghost != null) return List.of(ghost);
        ClientLevel level = sub.getLevel();
        List<SessionPortal> portals = SESSIONS.get(sub.getUniqueId());
        if (portals == null || portals.isEmpty()) return List.of();
        List<Portal> out = new ArrayList<>(portals.size());
        for (SessionPortal sessionPortal : portals) {
            Portal live = findPortal(level, sessionPortal.portalId());
            Portal resolved = live != null ? live : surrogate(sessionPortal, level);
            if (resolved != null) out.add(resolved);
        }
        return out;
    }

    /** Render-only counterpart of {@link #resolvePortal}; includes a delayed exit tail. */
    @Nullable
    public static Portal resolveRenderPortal(ClientSubLevel sub) {
        if (sub == null) return null;
        Portal ghost = IplParentDimSync.ghostPortalFor(sub);
        if (ghost != null) return ghost;
        List<Portal> portals = resolveRenderPortals(sub, sub.getLevel());
        return portals.isEmpty() ? null : portals.get(0);
    }

    /** Render-only counterpart of {@link #resolveAllPortals}; never use for collision. */
    public static List<Portal> resolveAllRenderPortals(ClientSubLevel sub) {
        if (sub == null) return List.of();
        Portal ghost = IplParentDimSync.ghostPortalFor(sub);
        if (ghost != null) return List.of(ghost);
        return resolveRenderPortals(sub, sub.getLevel());
    }

    /**
     * Chained sessions of {@code sub}, each resolved to its ordered portal path: the
     * root portal in the ship's own level, every next portal in the previous portal's
     * destination level (live entity, else snapshot surrogate). Unresolvable chains are
     * skipped this frame.
     */
    public static List<List<Portal>> resolveChains(ClientSubLevel sub) {
        if (sub == null) return List.of();
        List<SessionPortal> chains = CHAINS.get(sub.getUniqueId());
        if (chains == null || chains.isEmpty() || IplParentDimSync.isGhost(sub)) return List.of();
        List<SessionPortal> roots = SESSIONS.get(sub.getUniqueId());
        if (roots == null) return List.of();
        return resolvePaths(sub.getLevel(), roots, chains, null);
    }

    /**
     * Render-only counterpart of {@link #resolveChains}: also the chain tails the delayed render
     * pose still shows (sessions ended by a flip or a normal exit), and for a transit ghost the
     * successor's chains seen through the ghost's portal. Never use for collision.
     */
    public static List<List<Portal>> resolveRenderChains(ClientSubLevel sub) {
        if (sub == null) return List.of();
        ClientLevel level = sub.getLevel();
        if (IplParentDimSync.isGhost(sub)) return ghostRenderChains(sub, level);
        UUID id = sub.getUniqueId();
        List<Tail> tails = liveTails(sub, RETIRED_CHAINS);
        if (tails.isEmpty()) return resolveChains(sub);
        List<SessionPortal> chains = new ArrayList<>(CHAINS.getOrDefault(id, List.of()));
        for (Tail tail : tails) {
            if (byPath(chains, tail.entry().path()) == null) chains.add(tail.entry());
        }
        // Root prefixes: live, retired or handoff tail — whichever still carries the path.
        List<SessionPortal> roots = new ArrayList<>(SESSIONS.getOrDefault(id, List.of()));
        for (Tail tail : RETIRED.getOrDefault(id, List.of())) roots.add(tail.entry());
        SessionPortal handoff = HANDOFF_VISUALS.get(id);
        if (handoff != null) roots.add(handoff);
        return resolvePaths(level, roots, chains, null);
    }

    /**
     * При переносе в другое измерение старый объект живёт «призраком» и рисуется через портал
     * переноса, пока преемник не получит геометрию. Цепочки призрака — сессии преемника,
     * увиденные через портал переноса (тот же кусок в той же системе координат), а до первого
     * снимка преемника — хвосты цепочек старого кадра. Без них кусок за следующим порталом ряда
     * пропадал бы на всё время передачи, а проекция через портал переноса торчала бы за входом
     * следующего портала. Обратный портал (дорога назад) пропускается: это родная часть
     * призрака.
     */
    private static List<List<Portal>> ghostRenderChains(ClientSubLevel ghost, ClientLevel level) {
        Portal root = IplParentDimSync.ghostPortalFor(ghost);
        if (root == null) return List.of();
        UUID id = ghost.getUniqueId();
        String rootPath = root.getUUID().toString();
        List<SessionPortal> chains = new ArrayList<>(2);
        List<SessionPortal> successorRoots = SESSIONS.get(id);
        ClientLevel successorLevel = IplClientProjections.clientLevel(root.getDestDim());
        if (successorRoots != null && successorLevel != null) {
            Set<String> back = new HashSet<>();
            for (SessionPortal successorRoot : successorRoots) {
                Portal portal = resolveIn(successorLevel, successorRoot);
                if (portal != null && leadsBack(root, portal)) back.add(successorRoot.path());
                else chains.add(successorRoot.under(rootPath));
            }
            for (SessionPortal chain : CHAINS.getOrDefault(id, List.of())) {
                if (!back.contains(chain.path().substring(0, chain.path().indexOf('>')))) {
                    chains.add(chain.under(rootPath));
                }
            }
        }
        for (Tail tail : liveTails(ghost, GHOST_TAILS)) {
            if (byPath(chains, tail.entry().path()) == null) chains.add(tail.entry());
        }
        return chains.isEmpty() ? List.of() : resolvePaths(level, List.of(), chains, root);
    }

    /** Whether {@code candidate} is the way back through {@code crossed} (its reverse face pair). */
    private static boolean leadsBack(Portal crossed, Portal candidate) {
        return candidate.getDestDim() == crossed.level().dimension()
            && candidate.getOriginPos().distanceToSqr(crossed.getDestPos()) <= 1.0e-4
            && candidate.getDestPos().distanceToSqr(crossed.getOriginPos()) <= 1.0e-4;
    }

    /**
     * Cross-dimension move: before the frame switch drops the old chart, keep its chains under
     * {@code portalId} (live or already retired by the end-of-session snapshots) for the ghost.
     */
    public static void captureGhostTails(UUID shipId, UUID portalId) {
        String prefix = portalId + ">";
        List<Tail> tails = new ArrayList<>(2);
        for (Tail tail : RETIRED_CHAINS.getOrDefault(shipId, List.of())) {
            if (tail.entry().path().startsWith(prefix)) tails.add(tail);
        }
        for (SessionPortal chain : CHAINS.getOrDefault(shipId, List.of())) {
            if (!chain.path().startsWith(prefix) || tailByPath(tails, chain.path()) != null) continue;
            // Still live at the move: it ends exactly where the move's source chart ends.
            tails.add(new Tail(chain, Integer.MAX_VALUE));
        }
        if (tails.isEmpty()) GHOST_TAILS.remove(shipId);
        else GHOST_TAILS.put(shipId, List.copyOf(tails));
    }

    /** Ghost finished: its old-chart tails can no longer be drawn by anyone. */
    public static void clearGhostTails(UUID shipId) {
        if (GHOST_TAILS.remove(shipId) != null) discardUnreferencedSurrogates();
    }

    /**
     * Every chain path resolved to its ordered portals: the root in {@code level} (or
     * {@code fixedRoot}), every next portal in the previous portal's destination level.
     */
    private static List<List<Portal>> resolvePaths(
        ClientLevel level, List<SessionPortal> roots, List<SessionPortal> chains, @Nullable Portal fixedRoot
    ) {
        List<List<Portal>> out = new ArrayList<>(chains.size());
        for (SessionPortal chain : chains) {
            String[] ids = chain.path().split(">");
            List<Portal> resolved = new ArrayList<>(ids.length);
            ClientLevel at = level;
            String prefix = null;
            for (String id : ids) {
                prefix = prefix == null ? id : prefix + ">" + id;
                Portal portal;
                if (fixedRoot != null && resolved.isEmpty()) {
                    portal = fixedRoot.getUUID().toString().equals(id) ? fixedRoot : null;
                } else {
                    SessionPortal entry = byPath(prefix.indexOf('>') < 0 ? roots : chains, prefix);
                    portal = entry == null || at == null ? null : resolveIn(at, entry);
                }
                if (portal == null) { resolved = null; break; }
                resolved.add(portal);
                at = IplClientProjections.clientLevel(portal.getDestDim());
            }
            if (resolved != null && resolved.size() >= 2) out.add(List.copyOf(resolved));
        }
        return out;
    }

    @Nullable
    private static SessionPortal byPath(@Nullable List<SessionPortal> entries, String path) {
        if (entries == null) return null;
        for (SessionPortal entry : entries) {
            if (entry.path().equals(path)) return entry;
        }
        return null;
    }

    @Nullable
    private static Tail tailByPath(@Nullable List<Tail> tails, String path) {
        if (tails == null) return null;
        for (Tail tail : tails) {
            if (tail.entry().path().equals(path)) return tail;
        }
        return null;
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> out = new ArrayList<>(first.size() + second.size());
        out.addAll(first);
        out.addAll(second);
        return List.copyOf(out);
    }

    @Nullable
    private static Portal resolveIn(ClientLevel level, SessionPortal entry) {
        Portal live = findPortal(level, entry.portalId());
        return live != null ? live : surrogate(entry, level);
    }

    private static List<Portal> resolveRenderPortals(ClientSubLevel sub, ClientLevel level) {
        List<Portal> cached = IplStraddleRenderCache.renderPortals(sub);
        if (cached != null) return cached;
        List<Portal> resolved = resolveRenderPortalsUncached(sub, level);
        IplStraddleRenderCache.cacheRenderPortals(sub, resolved);
        return resolved;
    }

    private static List<Portal> resolveRenderPortalsUncached(ClientSubLevel sub, ClientLevel level) {
        List<SessionPortal> portals = portalsForRender(sub, level);
        List<SessionPortal> active = SESSIONS.get(sub.getUniqueId());
        List<Portal> out = new ArrayList<>(portals.size() + 1);
        List<Portal> visibleActive = new ArrayList<>(portals.size());
        for (SessionPortal sessionPortal : portals) {
            Portal live = findPortal(level, sessionPortal.portalId());
            Portal resolved = live != null ? live : surrogate(sessionPortal, level);
            if (resolved == null) continue;

            // A server session establishes parity, but its physics pose is ahead of Sable's
            // delayed render stream. Do not expose the split until this geometry reaches
            // the aperture; prediction below supplies the pre-RPC fast path.
            boolean isActive = active != null && containsPortal(active, sessionPortal.portalId());
            if (isActive && !IplClientVisualTransitLatch.isAuthoritativeVisible(sub, resolved, sessionPortal.completed())) continue;
            if (isActive) visibleActive.add(resolved);
            out.add(resolved);
        }
        SessionPortal handoffPortal = HANDOFF_VISUALS.get(sub.getUniqueId());
        if (handoffPortal != null && !containsPortal(portals, handoffPortal.portalId())) {
            Portal live = findPortal(level, handoffPortal.portalId());
            Portal resolvedHandoff = live != null ? live : surrogate(handoffPortal, level);
            if (resolvedHandoff != null && IplClientVisualTransitLatch.isAuthoritativeVisible(sub, resolvedHandoff, true)) {
                out.add(resolvedHandoff);
            }
        }
        return IplClientVisualTransitLatch.append(sub, level, visibleActive, out);
    }

    /** A retired session is geometry AND time bounded, never an infinite half-space. */
    private static boolean stillRenderingThroughHalf(ClientSubLevel sub, Portal portal, int endTick, boolean completed) {
        return beforeEndTick(sub, endTick) && IplClientVisualTransitLatch.isAuthoritativeVisible(sub, portal, completed);
    }

    /** Whether Sable's delayed render clock of {@code sub}'s level has not yet reached {@code endTick}. */
    private static boolean beforeEndTick(ClientSubLevel sub, int endTick) {
        var container = ClientSubLevelContainer.getContainer(sub.getLevel());
        if (container == null) return false;
        var clock = container.getInterpolation();
        return clock.lastInterpolationTick < endTick && !(clock.isStopped()
            && clock.getTickPointer() >= endTick + 1.5 - clock.getInterpolationDelay() - 1.0e-8);
    }

    /** Tails of {@code store} still ahead of the delayed render clock; expired ones are dropped. */
    private static List<Tail> liveTails(ClientSubLevel sub, ConcurrentMap<UUID, List<Tail>> store) {
        List<Tail> tails = store.get(sub.getUniqueId());
        if (tails == null || tails.isEmpty()) return List.of();
        List<Tail> live = new ArrayList<>(tails.size());
        for (Tail tail : tails) {
            if (beforeEndTick(sub, tail.endTick())) live.add(tail);
        }
        if (live.size() != tails.size()) {
            if (live.isEmpty()) store.remove(sub.getUniqueId(), tails);
            else store.replace(sub.getUniqueId(), tails, List.copyOf(live));
            discardUnreferencedSurrogates();
        }
        return live;
    }

    /** Active sessions plus only the retired sessions still present in the delayed pose. */
    private static List<SessionPortal> portalsForRender(ClientSubLevel sub, ClientLevel level) {
        List<SessionPortal> active = SESSIONS.get(sub.getUniqueId());
        List<Tail> retired = RETIRED.get(sub.getUniqueId());
        if (retired == null || retired.isEmpty()) {
            return active == null ? List.of() : active;
        }

        List<Tail> stillVisible = new ArrayList<>(retired.size());
        List<SessionPortal> visibleRetired = new ArrayList<>(retired.size());
        for (Tail tail : retired) {
            SessionPortal sessionPortal = tail.entry();
            Portal portal = findPortal(level, sessionPortal.portalId());
            if (portal == null) portal = surrogate(sessionPortal, level);
            if (portal != null && stillRenderingThroughHalf(sub, portal, tail.endTick(), sessionPortal.completed())) {
                stillVisible.add(tail);
                visibleRetired.add(sessionPortal);
            }
        }
        if (visibleRetired.isEmpty()) {
            RETIRED.remove(sub.getUniqueId(), retired);
            discardUnreferencedSurrogates();
            return active == null ? List.of() : active;
        }
        if (stillVisible.size() != retired.size()) {
            RETIRED.replace(sub.getUniqueId(), retired, List.copyOf(stillVisible));
        }
        if (active == null || active.isEmpty()) return visibleRetired;

        List<SessionPortal> combined = new ArrayList<>(active.size() + visibleRetired.size());
        combined.addAll(active);
        for (SessionPortal sessionPortal : visibleRetired) {
            if (!containsPortal(active, sessionPortal.portalId())) combined.add(sessionPortal);
        }
        return combined;
    }

    private static boolean containsPortal(List<SessionPortal> portals, UUID portalId) {
        for (SessionPortal portal : portals) {
            if (portal.portalId().equals(portalId)) return true;
        }
        return false;
    }

    /** Called by the atomic mapped-pose parent handoff; old-frame tails are invalid there. */
    public static void clearRetiredForHandoff(UUID shipId) {
        boolean dropped = RETIRED.remove(shipId) != null;
        if (RETIRED_CHAINS.remove(shipId) != null || dropped) discardUnreferencedSurrogates();
    }

    private static void discardUnreferencedSurrogates() {
        Set<UUID> referenced = new HashSet<>();
        collectReferencedPortals(SESSIONS, referenced);
        collectReferencedPortals(CHAINS, referenced);
        collectReferencedTails(RETIRED, referenced);
        collectReferencedTails(RETIRED_CHAINS, referenced);
        collectReferencedTails(GHOST_TAILS, referenced);
        for (SessionPortal portal : HANDOFF_VISUALS.values()) referenced.add(portal.portalId());
        SURROGATES.keySet().retainAll(referenced);
        SURROGATE_PAYLOADS.keySet().retainAll(referenced);
        SURROGATE_MISSES.keySet().retainAll(referenced);
    }

    private static void collectReferencedPortals(
        Map<UUID, List<SessionPortal>> sessions, Set<UUID> referenced
    ) {
        for (List<SessionPortal> list : sessions.values()) {
            for (SessionPortal sessionPortal : list) referenced.add(sessionPortal.portalId());
        }
    }

    private static void collectReferencedTails(Map<UUID, List<Tail>> tails, Set<UUID> referenced) {
        for (List<Tail> list : tails.values()) {
            for (Tail tail : list) referenced.add(tail.entry().portalId());
        }
    }

    @Nullable
    private static Portal findPortal(ClientLevel level, UUID portalId) {
        // O(1) by UUID through IP's own entity-index duck (IEWorld), never a scan of every
        // loaded entity: this runs inside Sable's spatial queries (see lookupPortal).
        Portal portal = IplClientVisualTransitLatch.lookupPortal(level, portalId);
        return portal != null && !portal.isRemoved() ? portal : null;
    }

    /** Global-portal-style reconstruction: type + NBT → detached Portal data carrier. */
    @Nullable
    private static Portal surrogate(SessionPortal sessionPortal, ClientLevel level) {
        Portal cached = SURROGATES.get(sessionPortal.portalId());
        if (cached != null && cached.level() == level
            && sessionPortal.portalNbtB64().equals(SURROGATE_PAYLOADS.get(sessionPortal.portalId()))) return cached;
        if (sessionPortal.portalNbtB64().isEmpty()) return null;
        SurrogateMiss miss = SURROGATE_MISSES.get(sessionPortal.portalId());
        if (miss != null && miss.level() == level && miss.payload().equals(sessionPortal.portalNbtB64())) return null;
        CompoundTag tag = decodeNbt(sessionPortal.portalNbtB64());
        // A future session can arrive before its queued parent flip. Its geometry
        // must not be reconstructed in the current/source dimension.
        if (tag.contains("ipl_source_dimension") && !tag.getString("ipl_source_dimension")
            .equals(level.dimension().location().toString())) return missSurrogate(sessionPortal, level);
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(tag.getString("entity_type")));
        Portal portal;
        try {
            // Loading runs the portal type's own NBT code (IP or another mod's subclass);
            // a type that cannot be rebuilt from geometry keys loses only its image.
            if (!(type.create(level) instanceof Portal created)) return missSurrogate(sessionPortal, level);
            created.load(tag);
            created.setUUID(sessionPortal.portalId());
            created.updateCache();
            portal = created;
        } catch (RuntimeException e) {
            LOG.error("[IPL-STRADDLE-SYNC] surrogate build failed for {}", sessionPortal.portalId(), e);
            return missSurrogate(sessionPortal, level);
        }
        SURROGATE_MISSES.remove(sessionPortal.portalId());
        SURROGATES.put(sessionPortal.portalId(), portal);
        SURROGATE_PAYLOADS.put(sessionPortal.portalId(), sessionPortal.portalNbtB64());
        return portal;
    }

    @Nullable
    private static Portal missSurrogate(SessionPortal sessionPortal, ClientLevel level) {
        SURROGATE_MISSES.put(sessionPortal.portalId(), new SurrogateMiss(sessionPortal.portalNbtB64(), level));
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

    /** Snapshot payload → session entries (roots and chains, in server order). */
    private static List<SessionPortal> parse(@Nullable String portalPayload) {
        if (portalPayload == null || portalPayload.isEmpty()) return List.of();
        List<SessionPortal> out = new ArrayList<>(2);
        for (String part : portalPayload.split(";")) {
            if (part.isEmpty()) continue;
            int sep = part.indexOf(':');
            String path = sep < 0 ? part : part.substring(0, sep);
            String encoded = sep < 0 ? "" : part.substring(sep + 1);
            boolean completed = false;
            Vec3 anchor = null;
            if (!encoded.isEmpty()) {
                CompoundTag tag = decodeNbt(encoded);
                completed = tag.getBoolean("ipl_session_completed");
                ListTag xyz = tag.getList("ipl_session_anchor", Tag.TAG_DOUBLE);
                if (xyz.size() == 3) anchor = new Vec3(xyz.getDouble(0), xyz.getDouble(1), xyz.getDouble(2));
            }
            int cut = path.lastIndexOf('>');
            UUID portalId = UUID.fromString(cut < 0 ? path : path.substring(cut + 1));
            out.add(new SessionPortal(portalId, encoded, completed, path, anchor));
        }
        return out;
    }

    private static void applySnapshot(UUID shipId, String portalPayload, int endTick) {
        List<SessionPortal> previousRoots = SESSIONS.get(shipId);
        List<SessionPortal> previousChains = CHAINS.get(shipId);
        List<SessionPortal> roots = new ArrayList<>(2);
        List<SessionPortal> chains = new ArrayList<>(0);
        for (SessionPortal entry : parse(portalPayload)) {
            (entry.isChain() ? chains : roots).add(entry);
        }
        if (roots.isEmpty()) {
            // A chain never outlives its root on the server; a rootless one is an ended tail.
            chains.clear();
            SESSIONS.remove(shipId);
            CHAINS.remove(shipId);
        } else {
            SESSIONS.put(shipId, List.copyOf(roots));
            if (chains.isEmpty()) CHAINS.remove(shipId);
            else CHAINS.put(shipId, List.copyOf(chains));
        }

        // Keep an ended split only until the delayed render pose clears its plane.
        // It covers both native eye-space and the portal render pass without keeping
        // a retired Atlas image collider alive on the server. Chains follow the same rule.
        retireMissing(RETIRED, shipId, previousRoots, roots, endTick);
        retireMissing(RETIRED_CHAINS, shipId, previousChains, chains, endTick);
        // The successor's own snapshot is authoritative over a ghost's old-chart tails.
        GHOST_TAILS.remove(shipId);
        discardUnreferencedSurrogates();

        // Straddle decisions are cached per frame; drop them so this snapshot
        // takes effect within the same client tick it arrives in.
        IplStraddleRenderCache.invalidateActivePasses();
        IplProjectionTree.invalidate();
    }

    /** Moves sessions missing from {@code next} into {@code retired}; a returning session is live again. */
    private static void retireMissing(
        ConcurrentMap<UUID, List<Tail>> retired, UUID shipId,
        @Nullable List<SessionPortal> previous, List<SessionPortal> next, int endTick
    ) {
        List<Tail> old = retired.get(shipId);
        if (old == null && previous == null) return;
        List<Tail> out = new ArrayList<>((old == null ? 0 : old.size()) + (previous == null ? 0 : previous.size()));
        if (old != null) {
            for (Tail tail : old) {
                if (byPath(next, tail.entry().path()) == null) out.add(tail);
            }
        }
        if (previous != null) {
            for (SessionPortal ended : previous) {
                if (byPath(next, ended.path()) == null && tailByPath(out, ended.path()) == null) {
                    out.add(new Tail(ended, endTick));
                }
            }
        }
        if (out.isEmpty()) retired.remove(shipId);
        else if (!out.equals(old)) retired.put(shipId, List.copyOf(out));
    }

    /** Records a snapshot of a frame the client has not entered yet (see {@link DeferredFrame}). */
    private static void defer(UUID shipId, long frame, String portalPayload, int endTick) {
        DeferredFrame deferred = FUTURE.computeIfAbsent(shipId, ignored -> new TreeMap<>())
            .computeIfAbsent(frame, ignored -> new DeferredFrame());
        List<SessionPortal> next = parse(portalPayload);
        for (SessionPortal ended : parse(deferred.payload)) {
            if (byPath(next, ended.path()) == null) deferred.ended.putIfAbsent(ended.path(), new Tail(ended, endTick));
        }
        for (SessionPortal live : next) deferred.ended.remove(live.path());
        deferred.payload = portalPayload;
        deferred.endTick = endTick;
    }

    public static final class RemoteCallables {

        /**
         * Full per-ship snapshot: ';'-joined {@code path:base64Nbt} entries (path = root
         * portal id, or {@code root>child>...} for chained sessions), empty = no sessions.
         */
        public static void snapshot(String shipUuid, CompoundTag sessions, String completedTick, String coordinateFrame) {
            UUID shipId = UUID.fromString(shipUuid);
            // Broadcasts include untracked ships. Their track-start bootstrap sends
            // the current frame/state; retaining every unknown loop epoch leaks memory.
            if (IplClientProjections.findClientSub(shipId) == null) return;
            // Компактный NBT-список -> внутренний формат "path:geometry;..."
            // (на клиенте у строки нет сетевого лимита).
            StringBuilder joined = new StringBuilder();
            ListTag list = sessions.getList("s", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag e = list.getCompound(i);
                if (!joined.isEmpty()) joined.append(';');
                joined.append(e.getString("p")).append(':').append(e.getString("g"));
            }
            String portalPayload = joined.toString();
            int endTick = Integer.parseInt(completedTick);
            long frame = Long.parseLong(coordinateFrame);
            if (frame < IplParentDimSync.frame(shipId)) return;
            if (frame > IplParentDimSync.frame(shipId)) {
                defer(shipId, frame, portalPayload, endTick);
                return;
            }
            applySnapshot(shipId, portalPayload, endTick);
        }
    }
}
