package ipl.sable.transit;

import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * Upstream/downstream order of portals that live in ONE frame: several root portals of one
 * body, or several child portals of one projection node.
 *
 * <p>Русский комментарий (почему): в ряду {@code ->|  |} (левый портал ведёт наверх, правый —
 * вниз) часть корабля, прошедшая левый портал, физически уже наверху, но её РОДНЫЕ координаты
 * продолжают идти вправо и через пару блоков пересекают правый портал. Раньше спорное
 * пространство за обоими порталами отдавалось по UUID пути (а трубка — раньше широкой
 * области), и правый портал мог забрать себе кусок левого: в физике, в дереве кусков и на
 * клиенте этот кусок показывался в нижнем портале. Геометрия однозначна: если проём Q лежит за
 * плоскостью P внутри области P, материал доходит до Q только через P, значит Q "ниже по
 * течению" и уступает P. Соосные соседи и две стороны одного проёма (глубина 0) и порталы,
 * смотрящие друг на друга (каждый за плоскостью другого), отношения не имеют — для них
 * остаётся прежний порядок.
 */
public final class PortalOrder {

    /** Lateral widening of every region tube (same value as the physics regions). */
    static final double LATERAL_MARGIN = 0.25;
    /** Depth below which two portals count as one plane (twin faces, neighbours in one wall). */
    static final double PLANE_TOLERANCE = 0.05;
    private static final double PARALLEL = 0.9999;
    private static final double EPSILON = 1.0e-6;

    private PortalOrder() {}

    /**
     * Whether {@code q}'s aperture lies beyond {@code p}'s plane, overlapping {@code p}'s region
     * ({@code pWide}: half-space instead of the aperture tube): material of the frame reaches
     * {@code q} only by going through {@code p} first.
     */
    public static boolean downstreamOf(Portal q, Portal p, boolean pWide) {
        Vec3 r = q.getOriginPos().subtract(p.getOriginPos());
        // Crossing direction is -normal (IP: the normal faces the source side).
        if (-r.dot(p.getNormal()) <= PLANE_TOLERANCE) return false;
        if (pWide) return true;
        Vec3 w = p.getAxisW();
        Vec3 h = p.getAxisH();
        return Math.abs(r.dot(w)) <= p.getWidth() * 0.5 + LATERAL_MARGIN + extent(q, w, 0.0) + EPSILON
            && Math.abs(r.dot(h)) <= p.getHeight() * 0.5 + LATERAL_MARGIN + extent(q, h, 0.0) + EPSILON;
    }

    /** {@link #downstreamOf} one way only; mutually downstream (facing) portals have no order. */
    public static boolean strictlyDownstreamOf(Portal q, boolean qWide, Portal p, boolean pWide) {
        return downstreamOf(q, p, pWide) && !downstreamOf(p, q, qWide);
    }

    /**
     * Whether {@code q}'s WHOLE region lies inside {@code p}'s: parallel planes crossed the same
     * way, {@code q} deeper, and {@code q}'s widened rectangle inside {@code p}'s tube (or
     * {@code p} wide). Once {@code p} outranks it, {@code q}'s piece is empty by construction, so
     * a projection through {@code q} has nothing to draw.
     */
    public static boolean coveredBy(Portal q, boolean qWide, Portal p, boolean pWide) {
        if (qWide && !pWide) return false;
        if (q.getNormal().dot(p.getNormal()) < PARALLEL) return false;
        Vec3 r = q.getOriginPos().subtract(p.getOriginPos());
        if (-r.dot(p.getNormal()) <= PLANE_TOLERANCE) return false;
        if (pWide) return true;
        Vec3 w = p.getAxisW();
        Vec3 h = p.getAxisH();
        return Math.abs(r.dot(w)) + extent(q, w, LATERAL_MARGIN) <= p.getWidth() * 0.5 + LATERAL_MARGIN + EPSILON
            && Math.abs(r.dot(h)) + extent(q, h, LATERAL_MARGIN) <= p.getHeight() * 0.5 + LATERAL_MARGIN + EPSILON;
    }

    /** Half-extent of {@code q}'s rectangle (widened by {@code margin}) along unit direction {@code d}. */
    private static double extent(Portal q, Vec3 d, double margin) {
        return Math.abs(q.getAxisW().dot(d)) * (q.getWidth() * 0.5 + margin)
            + Math.abs(q.getAxisH().dot(d)) * (q.getHeight() * 0.5 + margin);
    }
}
