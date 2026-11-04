package ipl.sable.transit;

import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * Upstream/downstream order of portals that live in ONE frame: several root portals of one
 * body, or several child portals of one projection node.
 *
 * <p>В ряду {@code ->|  |} (левый портал ведёт наверх, правый —
 * вниз) часть корабля, прошедшая левый портал, физически уже наверху, но её РОДНЫЕ координаты
 * продолжают идти вправо и через пару блоков пересекают правый портал. При порядке по UUID
 * пути правый портал мог бы забрать себе кусок левого (в физике, в дереве кусков и на клиенте
 * этот кусок показался бы в нижнем портале). Геометрия однозначна: если проём Q лежит за
 * плоскостью P внутри призмы P ({@link PortalPrism}), материал доходит до Q только через P,
 * значит Q "ниже по течению" и уступает P. Соосные соседи и две стороны одного проёма
 * (глубина 0) и порталы, смотрящие друг на друга (каждый за плоскостью другого), такого
 * отношения не имеют — для них действует порядок путей.
 */
public final class PortalOrder {

    /** Depth below which two portals count as one plane (twin faces, neighbours in one wall). */
    static final double PLANE_TOLERANCE = 0.05;
    private static final double EPSILON = 1.0e-6;

    private PortalOrder() {}

    /**
     * Whether {@code q}'s aperture lies beyond {@code p}'s plane, overlapping {@code p}'s prism:
     * material of the frame reaches {@code q} only by going through {@code p} first.
     */
    public static boolean downstreamOf(Portal q, PortalPrism p) {
        Vec3 r = q.getOriginPos().subtract(p.origin());
        if (r.dot(p.inward()) <= PLANE_TOLERANCE) return false;
        Vec3 w = p.dualW();
        Vec3 h = p.dualH();
        return Math.abs(r.dot(w)) <= p.halfW() + extent(q, w) + EPSILON
            && Math.abs(r.dot(h)) <= p.halfH() + extent(q, h) + EPSILON;
    }

    /** {@link #downstreamOf} one way only; mutually downstream (facing) portals have no order. */
    public static boolean strictlyDownstreamOf(Portal q, PortalPrism qPrism, Portal p, PortalPrism pPrism) {
        return downstreamOf(q, pPrism) && !downstreamOf(p, qPrism);
    }

    /** Half-extent of {@code q}'s aperture along {@code d} (any length: dual axes are not unit). */
    private static double extent(Portal q, Vec3 d) {
        return Math.abs(q.getAxisW().dot(d)) * q.getWidth() * 0.5
            + Math.abs(q.getAxisH().dot(d)) * q.getHeight() * 0.5;
    }
}
