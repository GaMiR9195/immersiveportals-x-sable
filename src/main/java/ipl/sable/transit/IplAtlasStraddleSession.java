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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Straddle sessions: lightweight far-side projections of a sub-level.
 *
 * <p>The real body stays the single source of truth. While it straddles a portal, an
 * IMAGE collider of the same rigid body is registered into the destination level's
 * Atlas chart with the portal isometry as its prefix ({@code image pose = P x body
 * pose}). Contacts on the real body beyond the portal plane and contacts on the image
 * behind the exit plane are neutralized by clip regions, so the pair behaves like one
 * body passing through the aperture. Forces on the image act on the real body.
 *
 * <p>Chains: when an image itself straddles another portal in its level, a child
 * session is opened with the composite prefix ({@code P_child x P_parent}). The parent
 * image gets an extra region hiding the part already beyond the child portal, and the
 * child image inherits the parent's "not yet through" regions. Depth is capped by
 * {@link #MAX_CHAIN_DEPTH}; nothing else limits chains.
 */
public final class IplAtlasStraddleSession {
    private static final Logger LOG = LoggerFactory.getLogger("ipl-atlas-straddle");
    private static final double APERTURE_MARGIN = 0.0;
    private static final double CLIP_SKIN = 1.0e-3;
    /** Sane recursion cap for projection-of-projection chains. */
    public static final int MAX_CHAIN_DEPTH = 8;

    private static final Map<StraddleKey, Session> SESSIONS = new LinkedHashMap<>();
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
        IplStraddlePoseMap.StraddleMapping local;
        IplStraddlePoseMap.StraddleMapping mapping;
        Vec3 sourceToDest;
        /** Region beyond this portal's plane, in the SOURCE level frame. */
        double[] beyondRegion;
        /** Region of the image behind this portal's exit, in the DEST frame. */
        double[] notThroughRegion;
        /** Parent chain's not-through regions mapped into this dest frame. */
        List<double[]> inheritedRegions = List.of();
        double[] lastImageRegions;
        long imageHandle = -1;
        boolean admitted;
        boolean completed;

        Session(StraddleKey key, ServerSubLevel sub, Portal portal, ServerLevel source, ServerLevel dest,
                long bodyScene, long destScene) {
            this.key = key;
            this.sub = sub;
            this.portal = portal;
            this.source = source;
            this.dest = dest;
            this.bodyLevel = sub.getLevel();
            this.bodyScene = bodyScene;
            this.destScene = destScene;
            this.realId = Rapier3D.getID(sub);
        }
    }

    public static boolean isEnabled() {
        return IplScenes.isEnabled();
    }

    // ------------------------------------------------------------------ lifecycle

    /** Opens or refreshes the ROOT session of the real body with {@code portal}. */
    public static void onStraddleTick(ServerSubLevel sub, Portal portal, Vec3 sourceToDest) {
        if (!(sub.getLevel() instanceof ServerLevel level) || portal.level() != level) return;
        ensure(new StraddleKey(sub.getUniqueId(), portal.getUUID()), null, sub, portal, sourceToDest, level);
    }

    /** Opens or refreshes a CHAINED session: the image of {@code parentKey} straddles {@code portal}. */
    public static void onChainTick(StraddleKey parentKey, Portal portal, Vec3 sourceToDest) {
        Session parent = SESSIONS.get(parentKey);
        if (parent == null || parentKey.depth() >= MAX_CHAIN_DEPTH || portal.level() != parent.dest) return;
        ensure(parentKey.child(portal.getUUID()), parent, parent.sub, portal, sourceToDest, parent.dest);
    }

    private static void ensure(StraddleKey key, @Nullable Session parent, ServerSubLevel sub,
                               Portal portal, Vec3 sourceToDest, ServerLevel source) {
        if (!isEnabled() || sub.isRemoved()) return;
        if (IplShipPortalAnchor.isAnchorShip(portal, sub.getUniqueId())) return;
        ServerLevel dest = source.getServer().getLevel(portal.getDestDim());
        if (dest == null) return;
        if (Math.abs(portal.getScaling() - 1.0) > 1e-9) {
            if (!loggedScaledSkip) {
                loggedScaledSkip = true;
                LOG.info("[IPL-IMAGE] scaled portals are not supported for sub-level projections "
                    + "(portal {}, logged once)", portal.getUUID());
            }
            return;
        }
        IplStraddlePoseMap.StraddleMapping local = IplStraddlePoseMap.StraddleMapping.of(portal);
        IplStraddlePoseMap.StraddleMapping mapping = parent == null ? local : parent.mapping.then(local);
        Session existing = SESSIONS.get(key);
        if (existing != null) {
            if (existing.dest != dest || existing.bodyLevel != sub.getLevel()
                || IplScenes.sceneHandle(existing.bodyLevel) != existing.bodyScene
                || IplScenes.sceneHandle(dest) != existing.destScene) {
                clear(key, "frame-invalidated");
            } else {
                refresh(existing, parent, local, mapping, sourceToDest);
                return;
            }
        }
        long bodyScene = IplScenes.sceneHandle((ServerLevel) sub.getLevel());
        long destScene = IplScenes.sceneHandle(dest);
        if (bodyScene == 0 || destScene == 0) return;
        Session session = new Session(key, sub, portal, source, dest, bodyScene, destScene);
        session.local = local;
        session.mapping = mapping;
        session.sourceToDest = sourceToDest;
        Vec3 shift = mapping.shift();
        org.joml.Quaterniond rot = mapping.mapQuat(new org.joml.Quaterniond());
        try {
            session.imageHandle = IplRapierNatives.createImageCollider(
                destScene, session.realId, shift.x, shift.y, shift.z, rot.x, rot.y, rot.z, rot.w);
        } catch (UnsatisfiedLinkError e) {
            session.imageHandle = -1;
        }
        if (session.imageHandle < 0) {
            LOG.warn("[IPL-IMAGE] image collider creation failed for ship {} portal {}",
                sub.getUniqueId(), portal.getUUID());
            return;
        }
        SESSIONS.put(key, session);
        computeRegions(session, parent);
        if (key.isRoot()) applyRealClipRegions(sub);
        applyImageRegions(session);
        if (parent != null) applyImageRegions(parent);
        LOG.debug("[IPL-IMAGE] start {} depth={} dest={} imageHandle={}",
            key.path(), key.depth(), dest.dimension().location(), session.imageHandle);
    }

    private static void refresh(Session s, @Nullable Session parent,
                                IplStraddlePoseMap.StraddleMapping local,
                                IplStraddlePoseMap.StraddleMapping mapping, Vec3 sourceToDest) {
        if (s.sub.isRemoved()) return;
        boolean moved = !mapping.sameAs(s.mapping);
        s.local = local;
        s.mapping = mapping;
        s.sourceToDest = sourceToDest;
        if (moved) {
            Vec3 shift = mapping.shift();
            org.joml.Quaterniond rot = mapping.mapQuat(new org.joml.Quaterniond());
            IplRapierNatives.setImagePrefix(s.destScene, s.imageHandle,
                shift.x, shift.y, shift.z, rot.x, rot.y, rot.z, rot.w);
        }
        double[] oldBeyond = s.beyondRegion;
        computeRegions(s, parent);
        if (!java.util.Arrays.equals(oldBeyond, s.beyondRegion)) {
            if (s.key.isRoot()) applyRealClipRegions(s.sub);
            if (parent != null) applyImageRegions(parent);
        }
        applyImageRegions(s);
    }

    private static void computeRegions(Session s, @Nullable Session parent) {
        Portal portal = s.portal;
        double depth = clipDepthLimit(s);
        double halfW = portal.getWidth() * 0.5 + APERTURE_MARGIN;
        double halfH = portal.getHeight() * 0.5 + APERTURE_MARGIN;
        Vec3 origin = portal.getOriginPos();
        s.beyondRegion = region(origin, s.sourceToDest, portal.getAxisW(), portal.getAxisH(),
            halfW, halfH, depth);
        s.notThroughRegion = transform(region(origin, s.sourceToDest.scale(-1.0),
            portal.getAxisW(), portal.getAxisH(), halfW, halfH, depth), s.local);
        if (parent == null) {
            s.inheritedRegions = List.of();
        } else {
            List<double[]> inherited = new ArrayList<>(parent.inheritedRegions.size() + 1);
            inherited.add(transform(parent.notThroughRegion, s.local));
            for (double[] r : parent.inheritedRegions) inherited.add(transform(r, s.local));
            s.inheritedRegions = inherited;
        }
    }

    /** Clip depth covers the whole body plus its distance from the portal plane. */
    private static double clipDepthLimit(Session s) {
        var box = s.sub.getPlot().getBoundingBox();
        double dx = box.maxX() - box.minX() + 1.0;
        double dy = box.maxY() - box.minY() + 1.0;
        double dz = box.maxZ() - box.minZ() + 1.0;
        double diagonal = Math.sqrt(dx * dx + dy * dy + dz * dz);
        Vec3 center = s.sub.logicalPose().transformPosition(new Vec3(
            (box.minX() + box.maxX() + 1.0) * .5,
            (box.minY() + box.maxY() + 1.0) * .5,
            (box.minZ() + box.maxZ() + 1.0) * .5));
        // Chained portals live in a projected frame: measure from the mapped centre.
        Session parent = s.key.isRoot() ? null : SESSIONS.get(parentKeyOf(s.key));
        if (parent != null) center = parent.mapping.mapPoint(center);
        double normalDistance = Math.abs(center.subtract(s.portal.getOriginPos()).dot(s.portal.getNormal()));
        return diagonal + normalDistance + APERTURE_MARGIN;
    }

    private static double[] region(Vec3 point, Vec3 normal, Vec3 axisW, Vec3 axisH,
                                   double halfW, double halfH, double maxDepth) {
        return new double[]{
            point.x, point.y, point.z,
            normal.x, normal.y, normal.z,
            axisW.x, axisW.y, axisW.z, halfW,
            axisH.x, axisH.y, axisH.z, halfH,
            CLIP_SKIN, maxDepth
        };
    }

    private static double[] transform(double[] r, IplStraddlePoseMap.StraddleMapping m) {
        Vec3 p = m.mapPoint(new Vec3(r[0], r[1], r[2]));
        Vec3 n = m.mapVec(new Vec3(r[3], r[4], r[5]));
        Vec3 w = m.mapVec(new Vec3(r[6], r[7], r[8]));
        Vec3 h = m.mapVec(new Vec3(r[10], r[11], r[12]));
        return region(p, n, w, h, r[9], r[13], r[15]);
    }

    private static double[] flatten(List<double[]> regions) {
        double[] flat = new double[regions.size() * 16];
        for (int i = 0; i < regions.size(); i++) System.arraycopy(regions.get(i), 0, flat, i * 16, 16);
        return flat;
    }

    /** Real body: union of the beyond-plane regions of all ROOT sessions. */
    private static void applyRealClipRegions(ServerSubLevel ship) {
        List<double[]> regions = new ArrayList<>(2);
        long scene = 0;
        int realId = -1;
        for (Session s : SESSIONS.values()) {
            if (s.sub != ship || !s.key.isRoot()) continue;
            regions.add(s.beyondRegion);
            scene = s.bodyScene;
            realId = s.realId;
        }
        if (realId < 0) {
            if (ship.isRemoved() || !(ship.getLevel() instanceof ServerLevel level)) return;
            scene = IplScenes.sceneHandle(level);
            realId = Rapier3D.getID(ship);
            if (scene == 0) return;
        }
        IplRapierNatives.setClipRegions(scene, realId, flatten(regions));
    }

    /** Image: own not-through region + inherited ones + regions beyond each child portal. */
    private static void applyImageRegions(Session s) {
        if (s.imageHandle < 0) return;
        List<double[]> regions = new ArrayList<>(2 + s.inheritedRegions.size());
        regions.add(s.notThroughRegion);
        regions.addAll(s.inheritedRegions);
        String path = s.key.path();
        for (Session child : SESSIONS.values()) {
            if (child.sub == s.sub && child.key.via().equals(path)) regions.add(child.beyondRegion);
        }
        double[] flat = flatten(regions);
        if (java.util.Arrays.equals(flat, s.lastImageRegions)) return;
        s.lastImageRegions = flat;
        IplRapierNatives.setImageClipRegions(s.destScene, s.realId, s.imageHandle, flat);
    }

    private static StraddleKey parentKeyOf(StraddleKey key) {
        String via = key.via();
        int split = via.lastIndexOf('>');
        UUID portal = UUID.fromString(split < 0 ? via : via.substring(split + 1));
        return new StraddleKey(key.subLevelUuid(), portal, split < 0 ? "" : via.substring(0, split));
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
        if (!session.sub.isRemoved() && session.sub.getLevel() == session.bodyLevel
            && IplScenes.sceneHandle(session.bodyLevel) == session.bodyScene) {
            if (key.isRoot()) {
                applyRealClipRegions(session.sub);
            } else {
                Session parent = SESSIONS.get(parentKeyOf(key));
                if (parent != null) applyImageRegions(parent);
            }
        }
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
        loggedScaledSkip = false;
    }

    // ------------------------------------------------------------------ state flags

    public static boolean isCompleted(StraddleKey key) {
        Session session = SESSIONS.get(key);
        return session != null && session.completed;
    }

    public static void revokeCompletion(StraddleKey key) {
        Session session = SESSIONS.get(key);
        if (session != null) session.completed = false;
    }

    public static void markCompleted(StraddleKey key) {
        Session session = SESSIONS.get(key);
        if (session != null) {
            session.admitted = true;
            session.completed = true;
        }
    }

    public static void markAdmitted(StraddleKey key) {
        Session session = SESSIONS.get(key);
        if (session != null) session.admitted = true;
    }

    public static boolean isAdmitted(StraddleKey key) {
        Session session = SESSIONS.get(key);
        return session != null && session.admitted;
    }

    // ------------------------------------------------------------------ queries

    /** True when no straddle session exists at all (cheap hot-path gate). */
    public static boolean isIdle() {
        return SESSIONS.isEmpty();
    }

    public static boolean hasSession(UUID shipUuid) {
        for (StraddleKey key : SESSIONS.keySet()) if (key.subLevelUuid().equals(shipUuid)) return true;
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

    /** Composite mapping real-body world -> session destination world. */
    @Nullable
    public static IplStraddlePoseMap.StraddleMapping sessionMapping(StraddleKey key) {
        Session s = SESSIONS.get(key);
        return s == null ? null : s.mapping;
    }

    /** Session portals of a chain parent's destination, used to exclude the exit face. */
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
        for (Session s : SESSIONS.values()) {
            if (s.dest == destLevel && s.sub.getUniqueId().equals(sub.getUniqueId())) return s.mapping;
        }
        return null;
    }

    @Nullable
    public static Portal getSessionPortalInto(SubLevel sub, Level level) {
        for (Session s : SESSIONS.values()) {
            if (s.dest == level && s.sub.getUniqueId().equals(sub.getUniqueId())) return s.portal;
        }
        return null;
    }

    /** Every projection of {@code sub} into {@code level}: (last portal, composite mapping). */
    public static void forEachSessionInto(
        SubLevel sub, Level level, BiConsumer<Portal, IplStraddlePoseMap.StraddleMapping> visitor
    ) {
        for (Session s : List.copyOf(SESSIONS.values())) {
            if (s.dest == level && s.sub.getUniqueId().equals(sub.getUniqueId())) {
                visitor.accept(s.portal, s.mapping);
            }
        }
    }

    /** ROOT sessions of the real body whose portal lives in {@code level}. */
    public static void forEachSessionFrom(
        SubLevel sub, Level level, BiConsumer<Portal, IplStraddlePoseMap.StraddleMapping> visitor
    ) {
        for (Session s : List.copyOf(SESSIONS.values())) {
            if (s.key.isRoot() && s.source == level && s.sub.getUniqueId().equals(sub.getUniqueId())) {
                visitor.accept(s.portal, s.mapping);
            }
        }
    }

    @Nullable
    public static Portal getSessionPortalFrom(SubLevel sub, Level level) {
        for (Session s : SESSIONS.values()) {
            if (s.key.isRoot() && s.source == level && s.sub.getUniqueId().equals(sub.getUniqueId())) {
                return s.portal;
            }
        }
        return null;
    }

    public static boolean isInMappedHalf(SubLevel sub, Level level, Vec3 position) {
        for (Session s : SESSIONS.values()) {
            if (s.key.isRoot() && s.dest == level && s.source == level
                && s.sub.getUniqueId().equals(sub.getUniqueId())) {
                return IplStraddlePoseMap.isInMappedHalf(s.mapping, s.portal, s.sub.boundingBox(), position);
            }
        }
        return false;
    }

    /** Every live projection into {@code destLevel}: (real sub-level, composite mapping). */
    public static void forEachSessionInto(
        ServerLevel destLevel, BiConsumer<ServerSubLevel, IplStraddlePoseMap.StraddleMapping> visitor
    ) {
        for (Session s : List.copyOf(SESSIONS.values())) {
            if (s.dest == destLevel && !s.sub.isRemoved()) visitor.accept(s.sub, s.mapping);
        }
    }
}
