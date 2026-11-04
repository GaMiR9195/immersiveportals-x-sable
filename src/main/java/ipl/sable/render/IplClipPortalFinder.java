package ipl.sable.render;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplStraddleRenderCache;
import ipl.sable.client.IplStraddleRenderState;
import ipl.sable.client.IplStraddleSessionStore;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.Plane;

import java.util.ArrayList;
import java.util.List;

/**
 * For a given client-side sub-level, the portal whose plane it currently straddles —
 * so {@link ChunkedRenderDataMixin} can install a clip plane that culls the geometry on
 * the dest side, and so client collision/interaction can select frames consistently.
 *
 * <p><b>Server-authoritative:</b> which half of a straddling ship is "still here" is
 * history, not geometry (a ship 60% through is geometrically identical to one 40% through
 * the other way), so nothing is guessed from geometry. The straddle portal comes from
 * {@code IplStraddleSessionStore} — the client mirror of the transit controller's latch —
 * and only the plane GEOMETRY is derived per frame from that portal entity's current
 * transform (moving portals stay smooth; parity switches exactly when the server's latch
 * does).
 */
public final class IplClipPortalFinder {

    private IplClipPortalFinder() {}

    /**
     * The chosen portal and its source-side clip plane. Convention: the kept
     * half-space is {@code normal · (p - pos) > 0}; the plane normal points to the
     * source side of the portal, where the ship's native frame remains.
     */
    public record ClipDecision(Portal portal, Plane plane) {}

    @Nullable
    public static ClipDecision findStraddlingPortalPlane(ClientSubLevel sub) {
        if (sub == null) return null;

        // Dest-side projection pass (dim-agnostic straddle rendering): the projection
        // driver has already computed the complementary plane — install that instead of
        // searching. This makes the legacy bracket the single clip installer for both
        // sides of a partial crossing.
        Plane projectionPlane =
            IplStraddleRenderState.getPlaneFor(sub);
        if (projectionPlane != null) {
            // The projection's kept half is NOT widened. The source bracket keeps
            // `n·(p-pos) >= 0` exactly on the mathematical portal plane and the projection
            // keeps the complementary half, so the two halves tile the sub-level exactly.
            // Shifting the cut by FrontClipping.ADJUSTMENT (IP's z-fighting guard for its
            // destination TERRAIN pass) would put a 1 cm slab inside BOTH halves and draw it
            // twice — a sub-pixel rim along the contour of anything poking through.
            return new ClipDecision(
                IplStraddleRenderState.getPortalFor(sub), projectionPlane);
        }

        if (IplStraddleRenderCache.hasDecision(sub)) {
            return IplStraddleRenderCache.decision(sub);
        }
        ClipDecision decision = ipl$findAuthoritative(sub);
        IplStraddleRenderCache.cacheDecision(sub, decision);
        return decision;
    }

    /**
     * The session store's portal, with the plane rebuilt from its live transform. The
     * session is keyed on the canonical ENTRANCE face, whose normal by IP convention
     * points at the remaining (source) half — exactly the kept side, no orientation
     * heuristic needed.
     */
    @Nullable
    private static ClipDecision ipl$findAuthoritative(ClientSubLevel sub) {
        Portal portal = IplStraddleSessionStore.resolveRenderPortal(sub);
        if (portal == null) return null;

        Vec3 origin = portal.getOriginPos();
        Vec3 keepNormal = portal.getNormal();
        return new ClipDecision(portal, new Plane(origin, keepNormal));
    }

    /**
     * Вторая плоскость среза для ТЕКУЩЕЙ отрисовки {@code sub}.
     *
     * <p>Пока армирована проекция — это её {@code exclude} (вход в следующий портал цепочки
     * или перенесённый выход предыдущего), иначе — плоскость второго корневого портала
     * (корабль одновременно в двух порталах). Один метод для всех вызывающих: кусок проекции
     * обрезается и на входе в следующий портал.
     */
    @Nullable
    public static Plane secondPlaneFor(ClientSubLevel sub) {
        if (sub == null) return null;
        if (IplStraddleRenderState.getPlaneFor(sub) != null) {
            return IplStraddleRenderState.getPlane2For(sub);
        }
        List<ClipDecision> all = findStraddlingPortalPlanes(sub);
        return all.size() > 1 ? all.get(1).plane() : null;
    }

    /**
     * ALL current straddle decisions for this sub (multi-straddle), session-start
     * order. The source render's kept region is the INTERSECTION of every decision's
     * half-space; the shader takes {@code min} over two cut planes, so brackets feed
     * the first two (more than two simultaneous straddles logs and clips the rest as
     * best-effort with the first pair).
     */
    public static List<ClipDecision> findStraddlingPortalPlanes(ClientSubLevel sub) {
        if (sub == null) return List.of();
        var cached = IplStraddleRenderCache.allDecisions(sub);
        if (cached != null) return cached;
        List<Portal> portals =
            IplStraddleSessionStore.resolveAllRenderPortals(sub);
        if (portals.isEmpty()) {
            IplStraddleRenderCache.cacheAllDecisions(sub, List.of());
            return List.of();
        }
        List<ClipDecision> out = new ArrayList<>(portals.size());
        for (Portal portal : portals) {
            out.add(new ClipDecision(portal, new Plane(portal.getOriginPos(), portal.getNormal())));
        }
        var result = List.copyOf(out);
        IplStraddleRenderCache.cacheAllDecisions(sub, result);
        return result;
    }
}
