package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.shape.RectangularPortalShape;
import qouteall.imm_ptl.core.portal.shape.SpecialFlatPortalShape;
import qouteall.q_misc_util.my_util.Plane;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Screen-space portal culling, allocation-free on the hot path.
 *
 * <p>A portal pass sees the world only through the screen rectangle of the portal it renders, and
 * a portal pass draws to the same pixels as the opening it was seen through. So every candidate
 * portal is reduced to the screen rectangle of its opening (four corners projected once with the
 * pass's view-projection), intersected with the rectangle of the enclosing pass, and dropped when
 * nothing of it is left. Nested portals and portal loops can only shrink and stop by themselves.
 *
 * <p>Only two cases need clipping, both rare: an opening crossing the pass's destination plane
 * (cut there, nothing behind it can be seen) and an opening crossing the camera's near plane. The
 * side planes are not clipped against: the rectangle is clamped to the parent's, which is exact
 * enough and conservative. An opening lying on the destination plane is culled (the far end of a
 * portal loop, which faces the viewer but can never be seen through the surface it is on).
 *
 * <p>Occlusion by blocks stays with the GPU query; {@link #minVisibleSamples} is its threshold.
 */
public final class PortalScreenCulling {
	private static final double GAP_FRACTION = Double.parseDouble(System.getProperty("ipl.portalMinVisibleFraction", "0.000025"));
	private static final float EDGE_MARGIN_PIXELS = 2.0f;
	private static final double ON_PLANE_EPSILON = 1.0e-3;
	private static final double NEAR_DISTANCE = 0.2;
	private static final int MAX_DEPTH = 64;

	// ---- region stack (NDC min x, min y, max x, max y) of the enclosing passes
	private static final float[][] REGIONS = new float[MAX_DEPTH + 1][4];
	private static int regionDepth = 0;

	// ---- candidate regions per layer
	private static final List<IdentityHashMap<Portal, float[]>> CANDIDATES = new ArrayList<>();

	// ---- state of the current candidate search (render thread only)
	private static final Matrix4f VP = new Matrix4f();
	private static double camX, camY, camZ;
	private static int screenW = 1, screenH = 1;
	private static float marginX, marginY;
	// scratch polygon: camera-relative points, then clip-space points
	private static final double[] PX = new double[8], PY = new double[8], PZ = new double[8], PD = new double[8];
	private static final double[] QX = new double[8], QY = new double[8], QZ = new double[8];
	private static final float[] CX = new float[8], CY = new float[8], CZ = new float[8], CW = new float[8];
	private static final float[] OUT = new float[4];

	static {
		REGIONS[0][0] = -1; REGIONS[0][1] = -1; REGIONS[0][2] = 1; REGIONS[0][3] = 1;
	}

	private PortalScreenCulling() {}

	/** Start of a candidate search in the pass at {@code layer}, with that pass's matrices. */
	public static void begin(int layer, Matrix4fc modelView, Matrix4fc projection, Vec3 cameraPos) {
		if (layer == 0) regionDepth = 0;
		while (CANDIDATES.size() <= layer) CANDIDATES.add(new IdentityHashMap<>());
		CANDIDATES.get(layer).clear();
		VP.set(projection).mul(modelView);
		camX = cameraPos.x;
		camY = cameraPos.y;
		camZ = cameraPos.z;
		RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
		screenW = Math.max(1, target.width);
		screenH = Math.max(1, target.height);
		marginX = EDGE_MARGIN_PIXELS * 2.0f / screenW;
		marginY = EDGE_MARGIN_PIXELS * 2.0f / screenH;
	}

	/** Whether {@code portal} can show anything in the current search; records its region. */
	public static boolean accept(Portal portal, int layer, @Nullable Plane clip) {
		float[] parent = REGIONS[regionDepth];
		if (portal.getDistanceToNearestPointInPortal(new Vec3(camX, camY, camZ)) < NEAR_DISTANCE) {
			// The eye is (almost) in the opening: IP draws it as a volume around the camera,
			// covering the whole region it is seen in.
			CANDIDATES.get(layer).put(portal, parent);
			return true;
		}
		boolean visible = isFlat(portal) ? flatRegion(portal, clip) : boxRegion(portal, clip);
		if (!visible) return false;
		float minX = Math.max(OUT[0] - marginX, parent[0]);
		float minY = Math.max(OUT[1] - marginY, parent[1]);
		float maxX = Math.min(OUT[2] + marginX, parent[2]);
		float maxY = Math.min(OUT[3] + marginY, parent[3]);
		if ((maxX - minX) * 0.5f * screenW < 1.0f || (maxY - minY) * 0.5f * screenH < 1.0f) return false;
		CANDIDATES.get(layer).put(portal, new float[]{minX, minY, maxX, maxY});
		return true;
	}

	/** {@code PortalRendering.pushPortalLayer}: the pass of {@code portal} sees only its region. */
	public static void onPushLayer(Portal portal, int outerLayer) {
		float[] region = outerLayer < CANDIDATES.size() ? CANDIDATES.get(outerLayer).get(portal) : null;
		float[] parent = REGIONS[regionDepth];
		if (regionDepth >= MAX_DEPTH) return;
		float[] next = REGIONS[++regionDepth];
		System.arraycopy(region != null ? region : parent, 0, next, 0, 4);
	}

	public static void onPopLayer() {
		if (regionDepth > 0) regionDepth--;
	}

	/**
	 * Samples a portal's opening must pass the depth and stencil tests with before its world is
	 * rendered: a fixed share of the screen for a gap in an occluder, but never more than a
	 * quarter of the portal's own screen region, so a small portal in plain view still renders.
	 */
	public static long minVisibleSamples(Portal portal, int layer) {
		double screen = (double) screenW * screenH;
		double gap = Math.max(4.0, screen * GAP_FRACTION);
		float[] region = layer < CANDIDATES.size() ? CANDIDATES.get(layer).get(portal) : null;
		if (region != null) {
			double area = (region[2] - region[0]) * 0.5 * screenW * (region[3] - region[1]) * 0.5 * screenH;
			gap = Math.min(gap, area * 0.25);
		}
		return Math.max(1L, (long) gap);
	}

	// ------------------------------------------------------------------ flat openings

	private static boolean isFlat(Portal portal) {
		return portal.getPortalShape() instanceof RectangularPortalShape
			|| portal.getPortalShape() instanceof SpecialFlatPortalShape;
	}

	private static boolean flatRegion(Portal portal, @Nullable Plane clip) {
		Vec3 o = portal.getOriginPos();
		Vec3 aw = portal.getAxisW();
		Vec3 ah = portal.getAxisH();
		double hw = Math.min(portal.getWidth(), 23333) * 0.5;
		double hh = Math.min(portal.getHeight(), 23333) * 0.5;
		double ox = o.x - camX, oy = o.y - camY, oz = o.z - camZ;
		double wx = aw.x * hw, wy = aw.y * hw, wz = aw.z * hw;
		double hx = ah.x * hh, hy = ah.y * hh, hz = ah.z * hh;
		PX[0] = ox - wx - hx; PY[0] = oy - wy - hy; PZ[0] = oz - wz - hz;
		PX[1] = ox + wx - hx; PY[1] = oy + wy - hy; PZ[1] = oz + wz - hz;
		PX[2] = ox + wx + hx; PY[2] = oy + wy + hy; PZ[2] = oz + wz + hz;
		PX[3] = ox - wx + hx; PY[3] = oy - wy + hy; PZ[3] = oz - wz + hz;
		int n = 4;

		if (clip != null) {
			Vec3 cn = clip.normal();
			double len = cn.length();
			if (len < 1e-9) return false;
			double nx = cn.x / len, ny = cn.y / len, nz = cn.z / len;
			Vec3 cp = clip.pos();
			double d0 = -(nx * (cp.x - camX) + ny * (cp.y - camY) + nz * (cp.z - camZ));
			boolean anyIn = false, anyOut = false, allOn = true;
			for (int i = 0; i < 4; i++) {
				double d = nx * PX[i] + ny * PY[i] + nz * PZ[i] + d0;
				PD[i] = d;
				if (Math.abs(d) > ON_PLANE_EPSILON) allOn = false;
				if (d >= 0) anyIn = true;
				else anyOut = true;
			}
			if (allOn || !anyIn) return false;
			if (anyOut) {
				n = clipPolygon(n, PD);
				if (n < 3) return false;
			}
		}
		return project(n);
	}

	// ------------------------------------------------------------------ other shapes

	private static boolean boxRegion(Portal portal, @Nullable Plane clip) {
		AABB box = portal.getThinBoundingBox();
		if (clip != null) {
			Vec3 cn = clip.normal();
			double x = cn.x >= 0 ? box.maxX : box.minX;
			double y = cn.y >= 0 ? box.maxY : box.minY;
			double z = cn.z >= 0 ? box.maxZ : box.minZ;
			if (cn.x * (x - clip.pos().x) + cn.y * (y - clip.pos().y) + cn.z * (z - clip.pos().z) < -ON_PLANE_EPSILON) {
				return false;
			}
		}
		for (int i = 0; i < 8; i++) {
			PX[i] = ((i & 1) == 0 ? box.minX : box.maxX) - camX;
			PY[i] = ((i & 2) == 0 ? box.minY : box.maxY) - camY;
			PZ[i] = ((i & 4) == 0 ? box.minZ : box.maxZ) - camZ;
		}
		// the corners of a box are a point cloud, not a polygon: if any is behind the near
		// plane the projected bound is not conservative, keep the parent region
		toClip(8);
		for (int i = 0; i < 8; i++) {
			if (CZ[i] < -CW[i] || CW[i] <= 1e-6f) {
				System.arraycopy(REGIONS[regionDepth], 0, OUT, 0, 4);
				return true;
			}
		}
		return bounds(8);
	}

	// ------------------------------------------------------------------ projection

	private static boolean project(int n) {
		toClip(n);
		boolean anyBehind = false;
		for (int i = 0; i < n; i++) {
			if (CZ[i] < -CW[i]) {
				anyBehind = true;
				break;
			}
		}
		if (anyBehind) {
			// clip against the near plane (z + w >= 0) in clip space
			for (int i = 0; i < n; i++) PD[i] = CZ[i] + CW[i];
			n = clipClipSpace(n);
			if (n < 3) return false;
		}
		return bounds(n);
	}

	private static void toClip(int n) {
		Matrix4f m = VP;
		for (int i = 0; i < n; i++) {
			float x = (float) PX[i], y = (float) PY[i], z = (float) PZ[i];
			CX[i] = m.m00() * x + m.m10() * y + m.m20() * z + m.m30();
			CY[i] = m.m01() * x + m.m11() * y + m.m21() * z + m.m31();
			CZ[i] = m.m02() * x + m.m12() * y + m.m22() * z + m.m32();
			CW[i] = m.m03() * x + m.m13() * y + m.m23() * z + m.m33();
		}
	}

	/** NDC bounds of the points (all in front of the near plane); false when off screen. */
	private static boolean bounds(int n) {
		int outLeft = 0, outRight = 0, outDown = 0, outUp = 0;
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		for (int i = 0; i < n; i++) {
			float w = CW[i];
			if (CX[i] < -w) outLeft++;
			if (CX[i] > w) outRight++;
			if (CY[i] < -w) outDown++;
			if (CY[i] > w) outUp++;
			if (w <= 1e-6f) continue;
			float x = CX[i] / w, y = CY[i] / w;
			if (x < minX) minX = x;
			if (y < minY) minY = y;
			if (x > maxX) maxX = x;
			if (y > maxY) maxY = y;
		}
		if (outLeft == n || outRight == n || outDown == n || outUp == n) return false;
		if (minX > maxX) return false;
		OUT[0] = Math.max(-1, minX);
		OUT[1] = Math.max(-1, minY);
		OUT[2] = Math.min(1, maxX);
		OUT[3] = Math.min(1, maxY);
		return OUT[0] < OUT[2] && OUT[1] < OUT[3];
	}

	// ------------------------------------------------------------------ clipping (rare paths)

	/** Keeps the part of the camera-relative polygon PX/PY/PZ where {@code dist >= 0}. */
	private static int clipPolygon(int n, double[] dist) {
		int m = 0;
		for (int i = 0; i < n; i++) {
			int j = (i + 1) % n;
			double da = dist[i], db = dist[j];
			if (da >= 0) {
				QX[m] = PX[i]; QY[m] = PY[i]; QZ[m] = PZ[i]; m++;
			}
			if ((da >= 0) != (db >= 0)) {
				double t = da / (da - db);
				QX[m] = PX[i] + (PX[j] - PX[i]) * t;
				QY[m] = PY[i] + (PY[j] - PY[i]) * t;
				QZ[m] = PZ[i] + (PZ[j] - PZ[i]) * t;
				m++;
			}
		}
		System.arraycopy(QX, 0, PX, 0, m);
		System.arraycopy(QY, 0, PY, 0, m);
		System.arraycopy(QZ, 0, PZ, 0, m);
		return m;
	}

	/** Keeps the part of the clip-space polygon CX..CW where {@code PD >= 0}. */
	private static int clipClipSpace(int n) {
		float[] x = new float[8], y = new float[8], z = new float[8], w = new float[8];
		int m = 0;
		for (int i = 0; i < n && m < 7; i++) {
			int j = (i + 1) % n;
			double da = PD[i], db = PD[j];
			if (da >= 0) {
				x[m] = CX[i]; y[m] = CY[i]; z[m] = CZ[i]; w[m] = CW[i]; m++;
			}
			if ((da >= 0) != (db >= 0)) {
				float t = (float) (da / (da - db));
				x[m] = CX[i] + (CX[j] - CX[i]) * t;
				y[m] = CY[i] + (CY[j] - CY[i]) * t;
				z[m] = CZ[i] + (CZ[j] - CZ[i]) * t;
				w[m] = CW[i] + (CW[j] - CW[i]) * t;
				m++;
			}
		}
		System.arraycopy(x, 0, CX, 0, m);
		System.arraycopy(y, 0, CY, 0, m);
		System.arraycopy(z, 0, CZ, 0, m);
		System.arraycopy(w, 0, CW, 0, m);
		return m;
	}
}
