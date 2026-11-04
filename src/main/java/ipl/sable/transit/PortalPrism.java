package ipl.sable.transit;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * THROUGH REGION of one straddle session, in the SOURCE frame of its portal: the aperture
 * rectangle (widened by {@link #LATERAL_MARGIN}) swept along the direction in which the
 * material actually passed it, from the portal plane to {@code maxDepth} behind it.
 *
 * <p>Только то, что прошло через проём, принадлежит порталу: часть тела, ушедшая за
 * бесконечную плоскость мимо рамки, остаётся родной и идёт дальше в исходном мире. Прямая
 * трубка по нормали теряла бы косо вошедший материал (корабль просел или повернул в проёме —
 * прошедшая часть вышла бы из трубки сбоку), поэтому призма наклонена вдоль хорды ЯКОРЯ:
 * точки тела, стоявшей в центре проёма при открытии сессии. Тело движется жёстко, значит
 * материал, прошедший через проём, лежит в проёме, сдвинутом вдоль пройденного пути.
 *
 * <p>Geometry: inward normal {@code n} (the controller's {@code sourceToDest}), passage
 * direction {@code d = n + s} with {@code s} perpendicular to {@code n}. A point belongs to the
 * prism when its depth {@code (p - o)·n} lies in {@code [-SKIN, maxDepth]} and its projection
 * back onto the plane along {@code d} lies in the widened aperture, i.e. when
 * {@code |(p - o)·w'| <= halfW} and {@code |(p - o)·h'| <= halfH} for the DUAL axes
 * {@code w' = w - (s·w) n}, {@code h' = h - (s·h) n}. That is exactly the native region
 * payload (axes are not normalized there), so physics, the projection tree, raycasts and the
 * client clip all evaluate one shape.
 */
public final class PortalPrism {

    /** Region skin in front of the plane (same value as the native contact clip). */
    public static final double SKIN = 1.0e-3;
    /** Lateral widening of every aperture (floor contacts sit on the aperture's bottom edge). */
    public static final double LATERAL_MARGIN = 0.25;
    /** Anchor depth from which the chord fully defines the slope; shorter chords fade to the normal. */
    static final double FULL_SLOPE_DEPTH = 0.5;
    /** Largest lateral slope of the passage (about 56 degrees off the normal). */
    static final double MAX_SLOPE = 1.5;
    private static final double EPSILON = 1.0e-12;

    private final double ox, oy, oz;
    private final double nx, ny, nz;
    private final double wx, wy, wz;
    private final double hx, hy, hz;
    private final double sx, sy, sz;
    private final double halfW, halfH, maxDepth;

    private PortalPrism(Portal portal, double sx, double sy, double sz, double maxDepth) {
        Vec3 o = portal.getOriginPos();
        Vec3 normal = portal.getNormal();
        Vec3 w = portal.getAxisW();
        Vec3 h = portal.getAxisH();
        this.ox = o.x;
        this.oy = o.y;
        this.oz = o.z;
        this.nx = -normal.x;
        this.ny = -normal.y;
        this.nz = -normal.z;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        double sw = sx * w.x + sy * w.y + sz * w.z;
        double sh = sx * h.x + sy * h.y + sz * h.z;
        this.wx = w.x - sw * nx;
        this.wy = w.y - sw * ny;
        this.wz = w.z - sw * nz;
        this.hx = h.x - sh * nx;
        this.hy = h.y - sh * ny;
        this.hz = h.z - sh * nz;
        this.halfW = portal.getWidth() * 0.5 + LATERAL_MARGIN;
        this.halfH = portal.getHeight() * 0.5 + LATERAL_MARGIN;
        this.maxDepth = maxDepth;
    }

    /** Straight tube along the normal (no passage observed yet, or a candidate portal). */
    public static PortalPrism straight(Portal portal, double maxDepth) {
        return new PortalPrism(portal, 0.0, 0.0, 0.0, maxDepth);
    }

    /**
     * The prism of a session whose anchor currently sits at {@code anchor} (SOURCE frame of
     * {@code portal}); null anchor = straight tube.
     */
    public static PortalPrism of(Portal portal, @Nullable Vec3 anchor, double maxDepth) {
        if (anchor == null) return straight(portal, maxDepth);
        Vec3 o = portal.getOriginPos();
        Vec3 normal = portal.getNormal();
        double dx = anchor.x - o.x, dy = anchor.y - o.y, dz = anchor.z - o.z;
        // Inward normal is -normal.
        double depth = -(dx * normal.x + dy * normal.y + dz * normal.z);
        if (depth <= 0.0) return straight(portal, maxDepth);
        double tx = dx + depth * normal.x;
        double ty = dy + depth * normal.y;
        double tz = dz + depth * normal.z;
        double k = depth >= FULL_SLOPE_DEPTH ? 1.0 / depth : depth / (FULL_SLOPE_DEPTH * FULL_SLOPE_DEPTH);
        double sx = tx * k, sy = ty * k, sz = tz * k;
        double slope = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (slope > MAX_SLOPE) {
            double f = MAX_SLOPE / slope;
            sx *= f;
            sy *= f;
            sz *= f;
        }
        return new PortalPrism(portal, sx, sy, sz, maxDepth);
    }

    public double halfW() {
        return halfW;
    }

    public double halfH() {
        return halfH;
    }

    public double maxDepth() {
        return maxDepth;
    }

    public Vec3 origin() {
        return new Vec3(ox, oy, oz);
    }

    /** Unit normal pointing INTO the portal (depth axis). */
    public Vec3 inward() {
        return new Vec3(nx, ny, nz);
    }

    /** Passage direction, scaled so that its depth component is 1. */
    public Vec3 passage() {
        return new Vec3(nx + sx, ny + sy, nz + sz);
    }

    /** Dual width axis: {@code (p - o)·dualW()} is the aperture coordinate of {@code p}'s passage. */
    public Vec3 dualW() {
        return new Vec3(wx, wy, wz);
    }

    public Vec3 dualH() {
        return new Vec3(hx, hy, hz);
    }

    /** Depth of {@code p} behind the portal plane. */
    public double depth(Vec3 p) {
        return (p.x - ox) * nx + (p.y - oy) * ny + (p.z - oz) * nz;
    }

    public boolean contains(Vec3 p) {
        return contains(p.x, p.y, p.z);
    }

    public boolean contains(double x, double y, double z) {
        double rx = x - ox, ry = y - oy, rz = z - oz;
        double depth = rx * nx + ry * ny + rz * nz;
        if (depth < -SKIN || depth > maxDepth) return false;
        return Math.abs(rx * wx + ry * wy + rz * wz) <= halfW
            && Math.abs(rx * hx + ry * hy + rz * hz) <= halfH;
    }

    /**
     * Ray-parameter interval {@code [t0, t1]} where {@code from + t * dir} is inside the prism;
     * null when never.
     */
    @Nullable
    public double[] interval(Vec3 from, Vec3 dir) {
        double rx = from.x - ox, ry = from.y - oy, rz = from.z - oz;
        double[] t = {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};
        if (!slab(t, rx * nx + ry * ny + rz * nz, dir.x * nx + dir.y * ny + dir.z * nz, -SKIN, maxDepth)) return null;
        if (!slab(t, rx * wx + ry * wy + rz * wz, dir.x * wx + dir.y * wy + dir.z * wz, -halfW, halfW)) return null;
        if (!slab(t, rx * hx + ry * hy + rz * hz, dir.x * hx + dir.y * hy + dir.z * hz, -halfH, halfH)) return null;
        return t[0] <= t[1] ? t : null;
    }

    private static boolean slab(double[] t, double s0, double sd, double lo, double hi) {
        if (Math.abs(sd) < EPSILON) return s0 >= lo && s0 <= hi;
        double ta = (lo - s0) / sd;
        double tb = (hi - s0) / sd;
        if (ta > tb) {
            double swap = ta;
            ta = tb;
            tb = swap;
        }
        if (ta > t[0]) t[0] = ta;
        if (tb < t[1]) t[1] = tb;
        return t[0] <= t[1];
    }

    /**
     * Native region (ABI 2, 17 doubles): {@code [o, n, w', halfW, h', halfH, skin, maxDepth, mode]}.
     */
    public double[] region(double mode) {
        return new double[]{
            ox, oy, oz,
            nx, ny, nz,
            wx, wy, wz, halfW,
            hx, hy, hz, halfH,
            SKIN, maxDepth, mode
        };
    }

    /**
     * The render faces as planes {@code {a, b, c, d}} (unit normal, kept where
     * {@code a x + b y + c z + d >= 0}): the portal plane first (kept side = beyond it), then
     * the four lateral faces. The far cap is omitted: {@code maxDepth} covers the whole body.
     */
    public double[][] faces() {
        double[][] out = new double[5][];
        out[0] = plane(nx, ny, nz, 0.0);
        out[1] = plane(-wx, -wy, -wz, halfW);
        out[2] = plane(wx, wy, wz, halfW);
        out[3] = plane(-hx, -hy, -hz, halfH);
        out[4] = plane(hx, hy, hz, halfH);
        return out;
    }

    /** Plane {@code g·(p - o) + offset >= 0}, normalized. */
    private double[] plane(double gx, double gy, double gz, double offset) {
        double len = Math.sqrt(gx * gx + gy * gy + gz * gz);
        double inv = len < EPSILON ? 0.0 : 1.0 / len;
        double a = gx * inv, b = gy * inv, c = gz * inv;
        return new double[]{a, b, c, offset * inv - (a * ox + b * oy + c * oz)};
    }
}
