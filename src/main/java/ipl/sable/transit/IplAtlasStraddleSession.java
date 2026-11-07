package ipl.sable.transit;

import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.atlas.IplScenes;
import ipl.sable.natives.IplRapierNatives;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Straddle sessions: the far-side images of a sub-level (README, section 2).
 *
 * <p>The real body stays the single source of truth. While part of it is through a portal,
 * an IMAGE collider of the same rigid body is registered into the destination level's Atlas
 * chart with the portal isometry as its prefix ({@code image pose = P x body pose}). Every
 * session is one node of the body's piece tree ({@link IplPieceMap}): the natives keep a
 * contact only on the collider of the piece its point belongs to, so real body and images
 * behave like one body passing through the apertures. Forces on an image act on the real body.
 *
 * <p>Chains: an image crossing a portal of its destination level is a child session with the
 * composite prefix ({@code P_child x P_parent}).
 * WHICH sessions exist is decided by {@link IplPieceTracker}; this class only owns the images.
 *
 * <p>Natives without piece clipping (stock, or an IPSable build older than
 * {@link IplRapierNatives#PIECE_ABI}) get sessions without an image collider: the body is
 * still split, drawn and flipped, but its far side does not collide (README, section 9).
 */
public final class IplAtlasStraddleSession {
    private static final Logger LOG = LoggerFactory.getLogger("ipl-atlas-straddle");

    private static final Map<StraddleKey, Session> SESSIONS = new LinkedHashMap<>();

    /**
     * Copy-on-write view of {@link #SESSIONS} for the hot read paths (every server spatial
     * query goes through {@code CompanionIntersectMixin}), rebuilt on each change.
     */
    private static volatile Session[] snapshot = new Session[0];

    /** Per destination level, the ships with at least one image into it, in first-session order. */
    private static volatile Map<ServerLevel, List<UUID>> shipsByDest = Map.of();
    private static boolean loggedScaledSkip;

    private IplAtlasStraddleSession() {}

    private static final class Session {
        final StraddleKey key;
        final ServerSubLevel sub;
        final Portal portal;
        /** Level containing {@link #portal}: the body's level for roots, the parent's dest otherwise. */
        final ServerLevel source;
        final ServerLevel dest;
        final ServerLevel bodyLevel;
        final long bodyScene;
        final long destScene;
        final int realId;
        /** Piece id of this session in the body's {@link IplPieceMap} (1..255). */
        final int node;
        IplStraddlePoseMap.StraddleMapping mapping;
        long imageHandle = -1;

        Session(StraddleKey key, ServerSubLevel sub, Portal portal, ServerLevel source, ServerLevel dest,
                long bodyScene, long destScene, int node) {
            this.key = key;
            this.sub = sub;
            this.portal = portal;
            this.source = source;
            this.dest = dest;
            this.bodyLevel = sub.getLevel();
            this.bodyScene = bodyScene;
            this.destScene = destScene;
            this.realId = Rapier3D.getID(sub);
            this.node = node;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Whether {@code portal} can carry a session at all (scale 1, live, loaded destination). */
    static boolean supports(ServerLevel source, Portal portal) {
        if (portal.isRemoved() || portal.level() != source) return false;
        if (Math.abs(portal.getScaling() - 1.0) > 1e-9) {
            if (!loggedScaledSkip) {
                loggedScaledSkip = true;
                LOG.info("[IPL-IMAGE] scaled portals are not supported for sub-level projections "
                    + "(portal {}, logged once)", portal.getUUID());
            }
            return false;
        }
        return source.getServer().getLevel(portal.getDestDim()) != null;
    }

    /**
     * Opens session {@code key} (its parent must exist for a chain) as piece {@code node}.
     * Returns false when it cannot be opened (missing scene, invalid portal or native failure).
     */
    static boolean open(StraddleKey key, ServerSubLevel sub, Portal portal, int node) {
        if (sub.isRemoved() || SESSIONS.containsKey(key)) return false;
        if (IplShipPortalAnchor.isAnchorShip(portal, sub.getUniqueId())) return false;
        Session parent = key.isRoot() ? null : SESSIONS.get(key.parent());
        if (!key.isRoot() && parent == null) return false;
        ServerLevel source = parent == null ? sub.getLevel() : parent.dest;
        if (!supports(source, portal)) return false;
        ServerLevel dest = source.getServer().getLevel(portal.getDestDim());
        long bodyScene = IplScenes.sceneHandle(sub.getLevel());
        long destScene = IplScenes.sceneHandle(dest);
        boolean physical = IplRapierNatives.piecesSupported();
        if (physical && (bodyScene == 0 || destScene == 0)) return false;
        Session session = new Session(key, sub, portal, source, dest, bodyScene, destScene, node);
        IplStraddlePoseMap.StraddleMapping local = IplStraddlePoseMap.StraddleMapping.of(portal);
        session.mapping = parent == null ? local : parent.mapping.then(local);
        if (physical) {
            Vec3 shift = session.mapping.shift();
            Quaterniond rot = session.mapping.mapQuat(new Quaterniond());
            session.imageHandle = IplRapierNatives.createImageCollider(
                destScene, session.realId, shift.x, shift.y, shift.z, rot.x, rot.y, rot.z, rot.w);
            if (session.imageHandle < 0) {
                LOG.warn("[IPL-IMAGE] image collider creation failed for ship {} portal {}",
                    sub.getUniqueId(), portal.getUUID());
                return false;
            }
            IplRapierNatives.setImageNode(destScene, session.realId, session.imageHandle, node);
        }
        SESSIONS.put(key, session);
        sessionsChanged();
        LOG.debug("[IPL-IMAGE] start {} node={} dest={}", key.path(), node, dest.dimension().location());
        return true;
    }

    /**
     * Follows moving portals (composite mapping, image prefix, client snapshot) and drops
     * sessions whose frame became invalid. Parents are refreshed before their children
     * (insertion order).
     */
    static void refreshShip(ServerSubLevel sub) {
        for (Session s : snapshot) {
            if (s.sub != sub || !SESSIONS.containsKey(s.key)) continue;
            Session parent = s.key.isRoot() ? null : SESSIONS.get(s.key.parent());
            if (s.portal.isRemoved() || s.bodyLevel != sub.getLevel() || (!s.key.isRoot() && parent == null)
                || IplScenes.sceneHandle(s.bodyLevel) != s.bodyScene || IplScenes.sceneHandle(s.dest) != s.destScene) {
                clear(s.key, "frame-invalidated");
                continue;
            }
            IplStraddlePoseMap.StraddleMapping local = IplStraddlePoseMap.StraddleMapping.of(s.portal);
            IplStraddlePoseMap.StraddleMapping mapping = parent == null ? local : parent.mapping.then(local);
            if (mapping.sameAs(s.mapping)) continue;
            s.mapping = mapping;
            if (s.imageHandle >= 0) {
                Vec3 shift = mapping.shift();
                Quaterniond rot = mapping.mapQuat(new Quaterniond());
                IplRapierNatives.setImagePrefix(s.destScene, s.imageHandle,
                    shift.x, shift.y, shift.z, rot.x, rot.y, rot.z, rot.w);
            }
            // Clients mirror the advertised geometry: a moved portal is re-advertised.
            IplStraddleSessionSync.onSessionStart(sub.getLevel().getServer(), sub, s.key, s.portal);
        }
    }

    /** Ends a session and every chained session below it. */
    public static void clear(StraddleKey key, String reason) {
        Session session = SESSIONS.remove(key);
        if (session == null) return;
        String prefix = key.path();
        for (StraddleKey other : new ArrayList<>(SESSIONS.keySet())) {
            if (other.subLevelUuid().equals(key.subLevelUuid())
                && (other.via().equals(prefix) || other.via().startsWith(prefix + ">"))) {
                clear(other, reason);
            }
        }
        if (session.imageHandle >= 0 && IplScenes.sceneHandle(session.dest) == session.destScene) {
            IplRapierNatives.removeImageCollider(session.destScene, session.realId, session.imageHandle);
        }
        sessionsChanged();
        LOG.debug("[IPL-IMAGE] end {} reason={}", key.path(), reason);
    }

    /** Ends every session of a ship (flip, removal, move). */
    public static void clearShip(UUID ship, String reason) {
        for (StraddleKey key : sessionKeysFor(ship)) {
            if (key.isRoot()) clear(key, reason);
        }
        for (StraddleKey key : sessionKeysFor(ship)) clear(key, reason);
    }

    public static void clearAll() {
        SESSIONS.clear();
        sessionsChanged();
        loggedScaledSkip = false;
    }

    // ------------------------------------------------------------------ promotion on flip

    /**
     * A descendant of the root a body flips through, re-keyed relative to that portal
     * ({@code P1>P2>P3} becomes {@code P2>P3}); it keeps its piece id, so the labels of the
     * material that went on through P2 stay valid in the new frame.
     */
    public record Promotion(String via, Portal portal, int node) {}

    /** Sessions below the root {@code root} as they look AFTER the body flipped through it, parents first. */
    public static List<Promotion> promotionsThrough(StraddleKey root) {
        if (!root.isRoot()) return List.of();
        String prefix = root.path();
        List<Promotion> out = new ArrayList<>();
        for (Session s : SESSIONS.values()) {
            if (!s.key.subLevelUuid().equals(root.subLevelUuid())) continue;
            String via = s.key.via();
            String newVia;
            if (via.equals(prefix)) newVia = "";
            else if (via.startsWith(prefix + ">")) newVia = via.substring(prefix.length() + 1);
            else continue;
            out.add(new Promotion(newVia, s.portal, s.node));
        }
        out.sort(Comparator.comparingInt(p -> p.via().isEmpty() ? 0 : 1 + p.via().split(">").length));
        return out;
    }

    /** Re-opens promoted sessions for the live body (after the flip); returns the opened keys. */
    static List<StraddleKey> promote(ServerSubLevel live, List<Promotion> promotions) {
        if (promotions.isEmpty() || live.isRemoved()) return List.of();
        List<StraddleKey> opened = new ArrayList<>(promotions.size());
        for (Promotion p : promotions) {
            StraddleKey key = new StraddleKey(live.getUniqueId(), p.portal().getUUID(), p.via());
            if (open(key, live, p.portal(), p.node())) opened.add(key);
        }
        return opened;
    }

    // ------------------------------------------------------------------ queries

    /** True when no straddle session exists at all (cheap hot-path gate). */
    public static boolean isIdle() {
        return SESSIONS.isEmpty();
    }

    public static boolean hasSession(UUID shipUuid) {
        for (Session s : snapshot) if (s.key.subLevelUuid().equals(shipUuid)) return true;
        return false;
    }

    public static boolean hasSessionKey(StraddleKey key) {
        return SESSIONS.containsKey(key);
    }

    public static List<StraddleKey> sessionKeys() {
        return new ArrayList<>(SESSIONS.keySet());
    }

    /** Keys of one ship, parents before children (insertion order). */
    public static List<StraddleKey> sessionKeysFor(UUID shipUuid) {
        List<StraddleKey> keys = new ArrayList<>();
        for (StraddleKey key : SESSIONS.keySet()) if (key.subLevelUuid().equals(shipUuid)) keys.add(key);
        return keys;
    }

    @Nullable
    public static Portal sessionPortal(StraddleKey key) {
        Session s = SESSIONS.get(key);
        return s == null ? null : s.portal;
    }

    @Nullable
    public static ServerLevel sessionDest(StraddleKey key) {
        Session s = SESSIONS.get(key);
        return s == null ? null : s.dest;
    }

    /** Piece id of a session, or {@link IplPieceMap#NATIVE} when it does not exist. */
    public static int sessionNode(StraddleKey key) {
        Session s = SESSIONS.get(key);
        return s == null ? IplPieceMap.NATIVE : s.node;
    }

    /** Composite mapping real-body world -> session destination world. */
    @Nullable
    public static IplStraddlePoseMap.StraddleMapping sessionMapping(StraddleKey key) {
        Session s = SESSIONS.get(key);
        return s == null ? null : s.mapping;
    }

    /** Direct children of a session. */
    public static List<StraddleKey> childrenOf(StraddleKey key) {
        String path = key.path();
        List<StraddleKey> out = new ArrayList<>();
        for (StraddleKey other : SESSIONS.keySet()) {
            if (other.subLevelUuid().equals(key.subLevelUuid()) && other.via().equals(path)) out.add(other);
        }
        return out;
    }

    /** First projection of {@code sub} into {@code destLevel} (any chain depth). */
    @Nullable
    public static IplStraddlePoseMap.StraddleMapping getMappingInto(SubLevel sub, Level destLevel) {
        for (Session s : snapshot) {
            if (s.dest == destLevel && s.sub.getUniqueId().equals(sub.getUniqueId())) return s.mapping;
        }
        return null;
    }

    /** Every projection of {@code sub} into {@code level}: (last portal, composite mapping). */
    public static void forEachSessionInto(
        SubLevel sub, Level level, BiConsumer<Portal, IplStraddlePoseMap.StraddleMapping> visitor
    ) {
        for (Session s : snapshot) {
            if (s.dest == level && s.sub.getUniqueId().equals(sub.getUniqueId())) {
                visitor.accept(s.portal, s.mapping);
            }
        }
    }

    /** Every live projection into {@code destLevel}: (real sub-level, composite mapping). */
    public static void forEachSessionInto(
        ServerLevel destLevel, BiConsumer<ServerSubLevel, IplStraddlePoseMap.StraddleMapping> visitor
    ) {
        for (Session s : snapshot) {
            if (s.dest == destLevel && !s.sub.isRemoved()) visitor.accept(s.sub, s.mapping);
        }
    }

    /**
     * Ships with at least one session image into {@code level}, in first-session order. The
     * only candidates the server spatial-query splice has to test for {@code level}.
     */
    public static List<UUID> shipsWithImagesInto(ServerLevel level) {
        return shipsByDest.getOrDefault(level, List.of());
    }

    /** Sessions of {@code sub} as projection-tree entries, parents before children. */
    public static List<IplProjectionTree.Entry> treeEntries(SubLevel sub) {
        List<IplProjectionTree.Entry> out = null;
        UUID id = sub.getUniqueId();
        for (Session s : snapshot) {
            if (!s.key.subLevelUuid().equals(id) || s.sub.isRemoved()) continue;
            if (out == null) out = new ArrayList<>(2);
            out.add(new IplProjectionTree.Entry(s.key.path(), s.key.via(), s.portal, s.node));
        }
        return out == null ? List.of() : out;
    }

    /** Rebuilds the hot-path views after every change of {@link #SESSIONS}. */
    private static void sessionsChanged() {
        Session[] all = SESSIONS.values().toArray(new Session[0]);
        Map<ServerLevel, List<UUID>> byDest = new IdentityHashMap<>();
        for (Session s : all) {
            List<UUID> ships = byDest.computeIfAbsent(s.dest, d -> new ArrayList<>(2));
            if (!ships.contains(s.key.subLevelUuid())) ships.add(s.key.subLevelUuid());
        }
        byDest.replaceAll((d, ships) -> List.copyOf(ships));
        snapshot = all;
        shipsByDest = byDest.isEmpty() ? Map.of() : byDest;
        IplProjectionTree.invalidate();
    }
}
