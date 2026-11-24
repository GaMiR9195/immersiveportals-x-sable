package qouteall.imm_ptl.core.teleportation;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;

/**
 * Passes on only the part of each quad on the kept side of a plane, cut exactly where the quad
 * crosses it. Positions are in the coordinates the particle draws in (relative to the camera).
 * Quads only (the mode of the particle buffer); one instance, reused, client thread only.
 */
final class ClippedQuadConsumer implements VertexConsumer {
    private static final int QUAD = 4;
    /** A quad cut by a plane keeps at most five corners. */
    private static final int MAX_CORNERS = 5;
    private static final int UV = 1, COLOR = 2, UV1 = 4, UV2 = 8, NORMAL = 16;

    private static final class Corner {
        float x, y, z, u, v, nx, ny, nz;
        int r, g, b, a, u1, v1, u2, v2, set;

        void copy(Corner o) {
            x = o.x; y = o.y; z = o.z; u = o.u; v = o.v; nx = o.nx; ny = o.ny; nz = o.nz;
            r = o.r; g = o.g; b = o.b; a = o.a; u1 = o.u1; v1 = o.v1; u2 = o.u2; v2 = o.v2; set = o.set;
        }

        /** The point at {@code t} from {@code p} to {@code q}; light and overlay of the nearer corner. */
        void between(Corner p, Corner q, float t) {
            copy(t < 0.5f ? p : q);
            x = lerp(p.x, q.x, t); y = lerp(p.y, q.y, t); z = lerp(p.z, q.z, t);
            u = lerp(p.u, q.u, t); v = lerp(p.v, q.v, t);
            r = Math.round(lerp(p.r, q.r, t)); g = Math.round(lerp(p.g, q.g, t));
            b = Math.round(lerp(p.b, q.b, t)); a = Math.round(lerp(p.a, q.a, t));
        }

        private static float lerp(float p, float q, float t) {
            return p + (q - p) * t;
        }
    }

    private final Corner[] quad = corners(QUAD);
    private final Corner[] kept = corners(MAX_CORNERS);
    private final float[] distance = new float[QUAD];
    private VertexConsumer target;
    private double px, py, pz, nx, ny, nz;
    private int count;

    private static Corner[] corners(int n) {
        Corner[] out = new Corner[n];
        for (int i = 0; i < n; i++) out[i] = new Corner();
        return out;
    }

    /** Starts clipping into {@code target}, keeping the side of the plane through {@code point} that {@code normal} points to. */
    ClippedQuadConsumer begin(VertexConsumer target, Vec3 point, Vec3 normal) {
        this.target = target;
        px = point.x; py = point.y; pz = point.z;
        nx = normal.x; ny = normal.y; nz = normal.z;
        count = 0;
        return this;
    }

    /** Emits the last quad; call once the particle drew. */
    void end() {
        if (count == QUAD) emit();
        count = 0;
        target = null;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        if (count == QUAD) emit();
        Corner c = quad[count++];
        c.set = 0;
        c.x = x; c.y = y; c.z = z;
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        Corner c = current();
        c.r = r; c.g = g; c.b = b; c.a = a;
        c.set |= COLOR;
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        Corner c = current();
        c.u = u; c.v = v;
        c.set |= UV;
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        Corner c = current();
        c.u1 = u; c.v1 = v;
        c.set |= UV1;
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        Corner c = current();
        c.u2 = u; c.v2 = v;
        c.set |= UV2;
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        Corner c = current();
        c.nx = x; c.ny = y; c.nz = z;
        c.set |= NORMAL;
        return this;
    }

    private Corner current() {
        return quad[Math.max(0, count - 1)];
    }

    private void emit() {
        count = 0;
        int inside = 0;
        for (int i = 0; i < QUAD; i++) {
            Corner c = quad[i];
            distance[i] = (float) ((c.x - px) * nx + (c.y - py) * ny + (c.z - pz) * nz);
            if (distance[i] >= 0.0f) inside++;
        }
        if (inside == 0) return;
        if (inside == QUAD) {
            for (Corner c : quad) put(c);
            return;
        }
        int n = 0;
        for (int i = 0; i < QUAD; i++) {
            int j = (i + 1) % QUAD;
            float di = distance[i], dj = distance[j];
            if (di >= 0.0f) kept[n++].copy(quad[i]);
            if ((di >= 0.0f) != (dj >= 0.0f)) kept[n++].between(quad[i], quad[j], di / (di - dj));
        }
        // Fan of quads, the last corner repeated where a triangle is left.
        put(kept[0]); put(kept[1]); put(kept[2]); put(kept[Math.min(3, n - 1)]);
        if (n == MAX_CORNERS) {
            put(kept[0]); put(kept[3]); put(kept[4]); put(kept[4]);
        }
    }

    private void put(Corner c) {
        target.addVertex(c.x, c.y, c.z);
        if ((c.set & UV) != 0) target.setUv(c.u, c.v);
        if ((c.set & COLOR) != 0) target.setColor(c.r, c.g, c.b, c.a);
        if ((c.set & UV1) != 0) target.setUv1(c.u1, c.v1);
        if ((c.set & UV2) != 0) target.setUv2(c.u2, c.v2);
        if ((c.set & NORMAL) != 0) target.setNormal(c.nx, c.ny, c.nz);
    }
}
