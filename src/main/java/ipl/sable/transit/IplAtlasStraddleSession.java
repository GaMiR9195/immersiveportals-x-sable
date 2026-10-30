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
    /**
     * Lateral widening of every aperture tube (real-body exclusion AND image keep tube,
     * so both sides of the seam stay one exact partition). Contacts with the floor under
     * a portal standing on the ground sit ON (or, with penetration, slightly below) the
     * aperture's bottom edge; with a zero margin they fell outside the tube, so the part
     * of the ship already through the portal kept resting on the SOURCE ground and the
     * image lost its destination floor at the same edge.
     */
    private static final double LATERAL_MARGIN = 0.25;
    private static final double CLIP_SKIN = 1.0e-3;
    /**
     * Боковой размер "широкой" области (полупространство, ограниченное только глубиной).
     *
     * <p>Русский комментарий (почему): трубки по проёму (+0.25) были верны только пока
     * корабль идёт строго по оси портала. Стоило ему наклониться/просесть (пол в Незере
     * ниже нижней кромки проёма), прошедшая часть выходила из трубки: её контакты с полом
     * назначения обрезались ("образ игнорирует мир"), а та же часть в исходном мире
     * (за плоскостью, но вне трубки) снова сталкивалась с землёй за порталом ("половина не
     * проходит сквозь землю"). Рендер же режет бесконечной плоскостью — физика и картинка
     * расходились. Если вход доказан и ВСЕ сечения тела на плоскости лежат в проёме
     * (retainsAdmittedSession) — либо тело уже целиком за плоскостью (completed) — то всё,
     * что за плоскостью, связано с остальным телом только через проём, то есть физически
     * находится в мире назначения. Тогда правильная область — полупространство.
     * Трубки остаются для непризнанных/предсказанных сессий и для "соседей" (несколько
     * корневых сессий одного тела или несколько дочерних у одного родителя), где
     * полупространства пересекались бы.
     */
    private static final double WIDE_EXTENT = 1.0e6;
    /** Sane recursion cap for projection-of-projection chains. */
    public static final int MAX_CHAIN_DEPTH = 8;
    /**
     * Жёсткий предел сессий на одно тело. Цепочки однонаправленных порталов подряд
     * раньше плодили фантомные сессии комбинаторно (каждая — образ в Rapier и запись в
     * снапшоте), что и роняло соединение. Нормальный проход через N порталов — это
     * N сессий; 24 с запасом покрывает реальные сценарии.
     */
    public static final int MAX_SESSIONS_PER_SHIP = 24;

    private static final Map<StraddleKey, Session> SESSIONS = new LinkedHashMap<>();
    private static boolean loggedScaledSkip;
    private static boolean loggedSessionCap;

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
        /** Region beyond this portal's plane, in the SOURCE level frame (EXCLUDE upstream). */
        double[] beyondRegion;
        /**
         * ABI 2: this image's KEEP set in the DEST frame -- the emerged tube of this portal
         * plus every ancestor's emerged tube mapped forward. A chained image is exactly
         * "what went through P1, then P2, ... then Pk": an INTERSECTION of tubes.
         */
        List<double[]> keepRegions = List.of();
        /** Legacy (ABI 1) only: region of the image behind this portal's exit, DEST frame. */
        double[] notThroughRegion;
        /** Legacy (ABI 1) only: parent chain's not-through regions mapped into this dest frame. */
        List<double[]> inheritedRegions = List.of();
        double[] lastImageRegions;
        long imageHandle = -1;
        boolean admitted;
        boolean completed;
        /** Controller verdict: physical entry proven and every section fits (or fully through). */
        boolean wideRequested;
        /** Effective region shape: {@link #wideRequested} and no sibling session. */
        boolean wide;

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

    /**
     * Opens or refreshes the ROOT session of the real body with {@code portal}.
     * {@code wide}: the controller proved the crossing is threaded through the aperture
     * (see {@link #WIDE_EXTENT}).
     */
    public static void onStraddleTick(ServerSubLevel sub, Portal portal, Vec3 sourceToDest, boolean wide) {
        if (!(sub.getLevel() instanceof ServerLevel level) || portal.level() != level) return;
        ensure(new StraddleKey(sub.getUniqueId(), portal.getUUID()), null, sub, portal, sourceToDest, level, wide);
    }

    /** Opens or refreshes a CHAINED session: the image of {@code parentKey} straddles {@code portal}. */
    public static void onChainTick(StraddleKey parentKey, Portal portal, Vec3 sourceToDest, boolean wide) {
        Session parent = SESSIONS.get(parentKey);
        if (parent == null || parentKey.depth() >= MAX_CHAIN_DEPTH || portal.level() != parent.dest) return;
        ensure(parentKey.child(portal.getUUID()), parent, parent.sub, portal, sourceToDest, parent.dest, wide);
    }

    private static void ensure(StraddleKey key, @Nullable Session parent, ServerSubLevel sub,
                               Portal portal, Vec3 sourceToDest, ServerLevel source, boolean wide) {
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
                existing.wideRequested = wide;
                refresh(existing, parent, local, mapping, sourceToDest);
                return;
            }
        }
        if (sessionCount(sub.getUniqueId()) >= MAX_SESSIONS_PER_SHIP) {
            if (!loggedSessionCap) {
                loggedSessionCap = true;
                LOG.warn("[IPL-IMAGE] ship {} reached {} straddle sessions; further projections are "
                    + "skipped (logged once)", sub.getUniqueId(), MAX_SESSIONS_PER_SHIP);
            }
            return;
        }
        long bodyScene = IplScenes.sceneHandle((ServerLevel) sub.getLevel());
        long destScene = IplScenes.sceneHandle(dest);
        if (bodyScene == 0 || destScene == 0) return;
        Session session = new Session(key, sub, portal, source, dest, bodyScene, destScene);
        session.local = local;
        session.mapping = mapping;
        session.sourceToDest = sourceToDest;
        session.wideRequested = wide;
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
        IplProjectionTree.invalidate();
        // Новая сессия может сделать соседей "не единственными" (wide -> трубка): пересчёт
        // всех областей тела, а не только своей.
        rebuildShip(sub);
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
        boolean oldWide = s.wide;
        computeRegions(s, parent);
        if (oldWide != s.wide) IplProjectionTree.invalidate();
        if (!java.util.Arrays.equals(oldBeyond, s.beyondRegion)) {
            if (s.key.isRoot()) applyRealClipRegions(s.sub);
            if (parent != null) applyImageRegions(parent);
        }
        applyImageRegions(s);
    }

    /** Recomputes every region of one body (parents first) and re-applies changed payloads. */
    private static void rebuildShip(ServerSubLevel sub) {
        boolean anyRoot = false;
        for (Session s : List.copyOf(SESSIONS.values())) {
            if (s.sub != sub) continue;
            Session parent = s.key.isRoot() ? null : SESSIONS.get(parentKeyOf(s.key));
            if (!s.key.isRoot() && parent == null) continue;
            computeRegions(s, parent);
            anyRoot |= s.key.isRoot();
        }
        if (anyRoot) applyRealClipRegions(sub);
        for (Session s : List.copyOf(SESSIONS.values())) {
            if (s.sub == sub) applyImageRegions(s);
        }
        IplProjectionTree.invalidate();
    }

    private static int sessionCount(UUID ship) {
        int n = 0;
        for (StraddleKey key : SESSIONS.keySet()) if (key.subLevelUuid().equals(ship)) n++;
        return n;
    }

    /** No other session competes for the same space: other roots of the body / other children of the parent. */
    private static boolean soleSibling(Session s) {
        for (Session other : SESSIONS.values()) {
            if (other == s || other.sub != s.sub) continue;
            if (other.key.via().equals(s.key.via())) return false;
        }
        return true;
    }

    private static void computeRegions(Session s, @Nullable Session parent) {
        Portal portal = s.portal;
        double depth = clipDepthLimit(s);
        s.wide = s.wideRequested && soleSibling(s);
        double halfW = s.wide ? WIDE_EXTENT : portal.getWidth() * 0.5 + LATERAL_MARGIN;
        double halfH = s.wide ? WIDE_EXTENT : portal.getHeight() * 0.5 + LATERAL_MARGIN;
        Vec3 origin = portal.getOriginPos();
        s.beyondRegion = region(origin, s.sourceToDest, portal.getAxisW(), portal.getAxisH(),
            halfW, halfH, depth, IplRapierNatives.REGION_EXCLUDE);
        // KEEP set: emerged tube of THIS portal + ancestors' keep tubes carried forward.
        double[] through = withMode(transform(s.beyondRegion, s.local), IplRapierNatives.REGION_KEEP);
        List<double[]> keeps = new ArrayList<>(1 + (parent == null ? 0 : parent.keepRegions.size()));
        keeps.add(through);
        if (parent != null) {
            for (double[] k : parent.keepRegions) keeps.add(transform(k, s.local));
        }
        s.keepRegions = keeps;
        // Legacy exclusion-only description (old natives without keep regions).
        s.notThroughRegion = transform(region(origin, s.sourceToDest.scale(-1.0),
            portal.getAxisW(), portal.getAxisH(), halfW, halfH, depth, IplRapierNatives.REGION_EXCLUDE), s.local);
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

    /** One region: 17 doubles, the ABI-2 layout (legacy payloads use the first 16). */
    private static double[] region(Vec3 point, Vec3 normal, Vec3 axisW, Vec3 axisH,
                                   double halfW, double halfH, double maxDepth, double mode) {
        return new double[]{
            point.x, point.y, point.z,
            normal.x, normal.y, normal.z,
            axisW.x, axisW.y, axisW.z, halfW,
            axisH.x, axisH.y, axisH.z, halfH,
            CLIP_SKIN, maxDepth, mode
        };
    }

    private static double[] withMode(double[] r, double mode) {
        double[] copy = r.clone();
        copy[16] = mode;
        return copy;
    }

    private static double[] transform(double[] r, IplStraddlePoseMap.StraddleMapping m) {
        Vec3 p = m.mapPoint(new Vec3(r[0], r[1], r[2]));
        Vec3 n = m.mapVec(new Vec3(r[3], r[4], r[5]));
        Vec3 w = m.mapVec(new Vec3(r[6], r[7], r[8]));
        Vec3 h = m.mapVec(new Vec3(r[10], r[11], r[12]));
        return region(p, n, w, h, r[9], r[13], r[15], r[16]);
    }

    private static boolean keepRegionsSupported() {
        return IplRapierNatives.abiLevel() >= 2;
    }

    /** ABI-2 payload (17 doubles per region, explicit mode). */
    private static double[] flatten(List<double[]> regions) {
        int stride = IplRapierNatives.REGION_STRIDE_V2;
        double[] flat = new double[regions.size() * stride];
        for (int i = 0; i < regions.size(); i++) System.arraycopy(regions.get(i), 0, flat, i * stride, stride);
        return flat;
    }

    /** Legacy payload (16 doubles per region, exclusion only). */
    private static double[] flattenLegacy(List<double[]> regions) {
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
        if (keepRegionsSupported()) {
            IplRapierNatives.setClipRegionsV2(scene, realId, flatten(regions));
        } else {
            IplRapierNatives.setClipRegions(scene, realId, flattenLegacy(regions));
        }
    }

    /**
     * Image regions. ABI 2: KEEP = this image's tube intersection (what actually went
     * through every portal of its path), EXCLUDE = the parts that already went on through
     * a child portal (owned by the child image). Legacy natives only understand
     * exclusions: own not-through region + inherited ones + child beyond regions -- which
     * leaves everything OUTSIDE the aperture tubes colliding (full copies with several
     * portals), hence the probe.
     */
    private static void applyImageRegions(Session s) {
        if (s.imageHandle < 0) return;
        boolean v2 = keepRegionsSupported();
        List<double[]> regions = new ArrayList<>(4 + s.inheritedRegions.size());
        if (v2) {
            regions.addAll(s.keepRegions);
        } else {
            regions.add(s.notThroughRegion);
            regions.addAll(s.inheritedRegions);
        }
        String path = s.key.path();
        for (Session child : SESSIONS.values()) {
            if (child.sub == s.sub && child.key.via().equals(path)) regions.add(child.beyondRegion);
        }
        double[] flat = v2 ? flatten(regions) : flattenLegacy(regions);
        if (java.util.Arrays.equals(flat, s.lastImageRegions)) return;
        s.lastImageRegions = flat;
        if (v2) {
            IplRapierNatives.setImageClipRegionsV2(s.destScene, s.realId, s.imageHandle, flat);
        } else {
            IplRapierNatives.setImageClipRegions(s.destScene, s.realId, s.imageHandle, flat);
        }
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
        IplProjectionTree.invalidate();
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
            if (key.isRoot()) applyRealClipRegions(session.sub);
            // Оставшийся сосед мог стать единственным (трубка -> полупространство).
            if (hasSession(key.subLevelUuid())) rebuildShip(session.sub);
            else if (!key.isRoot()) {
                Session parent = SESSIONS.get(parentKeyOf(key));
                if (parent != null) applyImageRegions(parent);
            }
        }
        LOG.debug("[IPL-IMAGE] end {} reason={}", key.path(), reason);
    }

    // ------------------------------------------------------------------ promotion on flip

    /**
     * A descendant of a flipped root, re-keyed relative to the portal the body just went
     * through ({@code P1>P2>P3} becomes {@code P2>P3}).
     */
    public record Promotion(String via, Portal portal, Vec3 sourceToDest,
                            boolean admitted, boolean completed, boolean wide) {}

    /**
     * Sessions below the root {@code root} as they look AFTER the body flipped through its
     * portal, parents first.
     *
     * <p>Русский комментарий (почему): флип закрывает все сессии тела. Когда выход P1
     * совпадает с входом P2 (ряд порталов) или тело во время флипа ещё стоит в P2, его
     * часть уже прошла P2 — а без сессии после флипа она оказывалась в исходном мире "за"
     * плоскостью P2 (и тело не проходило ряд дальше). Дочерние сессии P1>... повышаются в
     * корневые/цепочки нового кадра вместе с флагами admitted/completed/wide, и в том же
     * тике следующий раунд контроллера честно продолжает переход через P2, P3, ...
     */
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
            out.add(new Promotion(newVia, s.portal, s.sourceToDest, s.admitted, s.completed, s.wideRequested));
        }
        out.sort(java.util.Comparator.comparingInt(p -> p.via().isEmpty() ? 0 : 1 + p.via().split(">").length));
        return out;
    }

    /** Re-opens promoted sessions for the live body (after the flip); returns how many opened. */
    public static int promote(ServerSubLevel live, List<Promotion> promotions) {
        if (promotions.isEmpty() || live.isRemoved() || !(live.getLevel() instanceof ServerLevel level)) return 0;
        int opened = 0;
        for (Promotion p : promotions) {
            if (p.portal().isRemoved()) continue;
            StraddleKey key = new StraddleKey(live.getUniqueId(), p.portal().getUUID(), p.via());
            if (key.isRoot()) {
                if (p.portal().level() != level) continue;
                ensure(key, null, live, p.portal(), p.sourceToDest(), level, p.wide());
            } else {
                Session parent = SESSIONS.get(parentKeyOf(key));
                if (parent == null || p.portal().level() != parent.dest || key.depth() > MAX_CHAIN_DEPTH) continue;
                ensure(key, parent, live, p.portal(), p.sourceToDest(), parent.dest, p.wide());
            }
            Session s = SESSIONS.get(key);
            if (s == null) continue;
            s.admitted = p.admitted();
            s.completed = p.completed();
            opened++;
        }
        return opened;
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
        IplProjectionTree.invalidate();
        loggedScaledSkip = false;
        loggedSessionCap = false;
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

    /** Effective region shape of a session (half-space vs aperture tube); mirrored to clients. */
    public static boolean isWide(StraddleKey key) {
        Session session = SESSIONS.get(key);
        return session != null && session.wide;
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

    /**
     * Sessions of {@code sub} as projection-tree entries, parents before children
     * (insertion order: a child is only ever opened while its parent exists).
     */
    public static List<IplProjectionTree.Entry> treeEntries(SubLevel sub) {
        List<IplProjectionTree.Entry> out = null;
        UUID id = sub.getUniqueId();
        for (Session s : SESSIONS.values()) {
            if (!s.key.subLevelUuid().equals(id) || s.sub.isRemoved()) continue;
            if (out == null) out = new ArrayList<>(2);
            out.add(new IplProjectionTree.Entry(s.key.path(), s.key.via(), s.portal, s.wide));
        }
        return out == null ? List.of() : out;
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
