package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4d;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.shape.RectangularPortalShape;
import qouteall.imm_ptl.core.portal.shape.SpecialFlatPortalShape;
import qouteall.q_misc_util.my_util.Plane;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Screen-space portal culling.
 *
 * <p>A portal pass draws only into the pixels of the opening it is seen through. Every candidate
 * portal is therefore reduced to the screen rectangle of its opening (corners projected with the
 * pass's view-projection), intersected with the rectangle of the enclosing pass, and dropped when
 * nothing is left. Nested portals and portal loops shrink with every layer and stop by themselves.
 *
 * <p>Two cases need real clipping: an opening crossing the pass's destination plane (only the
 * part in front of it can be seen) and an opening crossing the camera's near plane. Side planes
 * are handled by clamping to the parent rectangle. An opening lying exactly on the destination
 * plane is culled: it is the far end of a portal loop and can never be seen through the surface
 * it lies on.
 *
 * <p>The pass behind a portal is also bounded in the world: the four planes through the eye and
 * the edges of its rectangle, carried through the portal, cull sections, entities and block
 * entities that cannot reach those pixels ({@link #getRegionPlanes}).
 *
 * <p>Occlusion by blocks is decided by the GPU query; {@link #minVisibleSamples} is its threshold.
 * {@link IPCGlobal#earlyFrustumCullingPortal} switches the screen test off (for comparisons).
 * Render thread only.
 */
public final class PortalScreenCulling {
	private static final double GAP_FRACTION =
		Double.parseDouble(System.getProperty("ipl.portalMinVisibleFraction", "0.000025"));
	private static final float EDGE_MARGIN_PIXELS = 2.0f;
	private static final double ON_PLANE_EPSILON = 1.0e-3;
	private static final double NEAR_DISTANCE = 0.2;
	// NDC depths of the two points that span each region plane (about 0.1 and 1 block away)
	private static final double PLANE_NEAR_NDC_Z = 0.0;
	private static final double PLANE_FAR_NDC_Z = 0.9;
	private static final float FULL_SCREEN_EDGE = 0.999f;
	private static final int MAX_DEPTH = 64;
	private static final int MAX_POINTS = 10;

	/** NDC rectangles (min x, min y, max x, max y) of the enclosing passes; [0] is the screen. */
	private static final float[][] REGIONS = new float[MAX_DEPTH + 1][4];
	private static int regionDepth = 0;

	/** Side planes (a, b, c, d each; world coordinates of that pass) bounding each pass. */
	private static final double[][] REGION_PLANES = new double[MAX_DEPTH + 1][16];
	private static final boolean[] HAS_REGION_PLANES = new boolean[MAX_DEPTH + 1];

	/** View-projection and camera of the candidate search at each layer. */
	private static final Matrix4f[] LAYER_VIEW_PROJECTION = new Matrix4f[MAX_DEPTH + 1];
	private static final Vec3[] LAYER_CAMERA = new Vec3[MAX_DEPTH + 1];

	/** Accepted portals of the search at each layer, with their rectangles. */
	private static final List<IdentityHashMap<Portal, float[]>> CANDIDATES = new ArrayList<>();

	private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
	private static Vec3 cameraPos = Vec3.ZERO;
	private static int screenWidth = 1;
	private static int screenHeight = 1;
	private static float marginX;
	private static float marginY;

	// scratch polygon: camera-relative points, then clip-space points
	private static final double[] PX = new double[MAX_POINTS];
	private static final double[] PY = new double[MAX_POINTS];
	private static final double[] PZ = new double[MAX_POINTS];
	private static final double[] DIST = new double[MAX_POINTS];
	private static final double[] QX = new double[MAX_POINTS];
	private static final double[] QY = new double[MAX_POINTS];
	private static final double[] QZ = new double[MAX_POINTS];
	private static final float[] CX = new float[MAX_POINTS];
	private static final float[] CY = new float[MAX_POINTS];
	private static final float[] CZ = new float[MAX_POINTS];
	private static final float[] CW = new float[MAX_POINTS];
	private static final float[] KX = new float[MAX_POINTS];
	private static final float[] KY = new float[MAX_POINTS];
	private static final float[] KZ = new float[MAX_POINTS];
	private static final float[] KW = new float[MAX_POINTS];
	private static final float[] OUT = new float[4];

	static {
		setFullScreen(REGIONS[0]);
		for (int i = 0; i <= MAX_DEPTH; i++) {
			LAYER_VIEW_PROJECTION[i] = new Matrix4f();
			LAYER_CAMERA[i] = Vec3.ZERO;
		}
	}

	private PortalScreenCulling() {}

	/** Start of a candidate search in the pass at {@code layer}, with that pass's matrices. */
	public static void begin(int layer, Matrix4fc modelView, Matrix4fc projection, Vec3 camera) {
		if (layer == 0) regionDepth = 0;
		while (CANDIDATES.size() <= layer) CANDIDATES.add(new IdentityHashMap<>());
		CANDIDATES.get(layer).clear();
		VIEW_PROJECTION.set(projection).mul(modelView);
		cameraPos = camera;
		if (layer <= MAX_DEPTH) {
			LAYER_VIEW_PROJECTION[layer].set(VIEW_PROJECTION);
			LAYER_CAMERA[layer] = camera;
		}
		RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
		screenWidth = Math.max(1, target.width);
		screenHeight = Math.max(1, target.height);
		marginX = EDGE_MARGIN_PIXELS * 2.0f / screenWidth;
		marginY = EDGE_MARGIN_PIXELS * 2.0f / screenHeight;
	}

	/** Whether {@code portal} can show anything in the current search; records its rectangle. */
	public static boolean accept(Portal portal, int layer, @Nullable Plane clip) {
		float[] parent = REGIONS[regionDepth];
		if (!IPCGlobal.earlyFrustumCullingPortal
			|| portal.getDistanceToNearestPointInPortal(cameraPos) < NEAR_DISTANCE
		) {
			// An opening around the eye is drawn as a volume covering the whole parent region.
			CANDIDATES.get(layer).put(portal, parent.clone());
			return true;
		}
		boolean visible = isFlat(portal) ? flatRegion(portal, clip) : boxRegion(portal, clip);
		if (!visible) return false;
		float minX = Math.max(OUT[0] - marginX, parent[0]);
		float minY = Math.max(OUT[1] - marginY, parent[1]);
		float maxX = Math.min(OUT[2] + marginX, parent[2]);
		float maxY = Math.min(OUT[3] + marginY, parent[3]);
		if ((maxX - minX) * 0.5f * screenWidth < 1.0f || (maxY - minY) * 0.5f * screenHeight < 1.0f) {
			return false;
		}
		CANDIDATES.get(layer).put(portal, new float[]{minX, minY, maxX, maxY});
		return true;
	}

	/** {@code PortalRendering.pushPortalLayer}: the pass of {@code portal} sees only its rectangle. */
	public static void onPushLayer(Portal portal, int outerLayer) {
		if (regionDepth >= MAX_DEPTH) return;
		float[] region = outerLayer < CANDIDATES.size() ? CANDIDATES.get(outerLayer).get(portal) : null;
		float[] parent = REGIONS[regionDepth];
		float[] own = REGIONS[++regionDepth];
		System.arraycopy(region != null ? region : parent, 0, own, 0, 4);
		HAS_REGION_PLANES[regionDepth] = region != null
			&& outerLayer <= MAX_DEPTH
			&& !isFullScreen(own)
			&& computeRegionPlanes(portal, outerLayer, own, REGION_PLANES[regionDepth]);
	}

	public static void onPopLayer() {
		if (regionDepth > 0) regionDepth--;
	}

	/**
	 * Samples a portal's opening must pass the depth and stencil tests with before its world is
	 * rendered: a fixed share of the screen (a gap between blocks is not worth a world pass), but
	 * never more than a quarter of the portal's own rectangle, so a small portal in plain view
	 * still renders.
	 */
	public static long minVisibleSamples(Portal portal, int layer) {
		double threshold = Math.max(4.0, (double) screenWidth * screenHeight * GAP_FRACTION);
		float[] region = layer < CANDIDATES.size() ? CANDIDATES.get(layer).get(portal) : null;
		if (region != null) {
			double area = (region[2] - region[0]) * 0.5 * screenWidth * (region[3] - region[1]) * 0.5 * screenHeight;
			threshold = Math.min(threshold, area * 0.25);
		}
		return Math.max(1L, (long) threshold);
	}

	/**
	 * Side planes of the portal pass being rendered (a, b, c, d per plane; inside where
	 * {@code a*x + b*y + c*z + d >= 0} in world coordinates), or null when it is not narrowed.
	 */
	public static double @Nullable [] getRegionPlanes() {
		return regionDepth > 0 && HAS_REGION_PLANES[regionDepth] ? REGION_PLANES[regionDepth] : null;
	}

	private static boolean isFullScreen(float[] rect) {
		return rect[0] <= -FULL_SCREEN_EDGE && rect[1] <= -FULL_SCREEN_EDGE
			&& rect[2] >= FULL_SCREEN_EDGE && rect[3] >= FULL_SCREEN_EDGE;
	}

	/**
	 * Planes through the outer pass's eye and the edges of {@code rect}, carried through
	 * {@code portal} into the world of the pass behind it. The pass sees the same pixels, so
	 * anything outside these planes cannot show up in it.
	 */
	private static boolean computeRegionPlanes(Portal portal, int outerLayer, float[] rect, double[] out) {
		Matrix4d inverse = new Matrix4d(LAYER_VIEW_PROJECTION[outerLayer]).invert();
		Vec3 eye = LAYER_CAMERA[outerLayer];
		double[] xs = {rect[0], rect[2], rect[2], rect[0]};
		double[] ys = {rect[1], rect[1], rect[3], rect[3]};
		Vec3[] near = new Vec3[4];
		Vec3[] far = new Vec3[4];
		for (int i = 0; i < 4; i++) {
			near[i] = unproject(inverse, xs[i], ys[i], PLANE_NEAR_NDC_Z, eye, portal);
			far[i] = unproject(inverse, xs[i], ys[i], PLANE_FAR_NDC_Z, eye, portal);
			if (near[i] == null || far[i] == null) return false;
		}
		Vec3 inside = unproject(
			inverse, (rect[0] + rect[2]) * 0.5, (rect[1] + rect[3]) * 0.5, PLANE_FAR_NDC_Z, eye, portal
		);
		if (inside == null) return false;
		for (int i = 0; i < 4; i++) {
			Vec3 a = near[i];
			Vec3 normal = far[i].subtract(a).cross(far[(i + 1) % 4].subtract(a));
			double length = normal.length();
			if (length < 1e-12) return false;
			normal = normal.scale(1.0 / length);
			double d = -normal.dot(a);
			if (normal.dot(inside) + d < 0) {
				normal = normal.reverse();
				d = -d;
			}
			out[i * 4] = normal.x;
			out[i * 4 + 1] = normal.y;
			out[i * 4 + 2] = normal.z;
			out[i * 4 + 3] = d;
		}
		return true;
	}

	/** World point (outer pass) at an NDC position, carried through {@code portal}. */
	private static @Nullable Vec3 unproject(Matrix4d inverse, double x, double y, double z, Vec3 eye, Portal portal) {
		Vector4d v = inverse.transform(new Vector4d(x, y, z, 1));
		if (Math.abs(v.w) < 1e-12) return null;
		return portal.transformPoint(new Vec3(v.x / v.w + eye.x, v.y / v.w + eye.y, v.z / v.w + eye.z));
	}

	private static void setFullScreen(float[] region) {
		region[0] = -1;
		region[1] = -1;
		region[2] = 1;
		region[3] = 1;
	}

	// ------------------------------------------------------------------ flat openings

	public static boolean isFlat(Portal portal) {
		return portal.getPortalShape() instanceof RectangularPortalShape
			|| portal.getPortalShape() instanceof SpecialFlatPortalShape;
	}

	private static boolean flatRegion(Portal portal, @Nullable Plane clip) {
		Vec3 o = portal.getOriginPos();
		Vec3 aw = portal.getAxisW();
		Vec3 ah = portal.getAxisH();
		double hw = Math.min(portal.getWidth(), 23333) * 0.5;
		double hh = Math.min(portal.getHeight(), 23333) * 0.5;
		double ox = o.x - cameraPos.x, oy = o.y - cameraPos.y, oz = o.z - cameraPos.z;
		double wx = aw.x * hw, wy = aw.y * hw, wz = aw.z * hw;
		double hx = ah.x * hh, hy = ah.y * hh, hz = ah.z * hh;
		setPoint(0, ox - wx - hx, oy - wy - hy, oz - wz - hz);
		setPoint(1, ox + wx - hx, oy + wy - hy, oz + wz - hz);
		setPoint(2, ox + wx + hx, oy + wy + hy, oz + wz + hz);
		setPoint(3, ox - wx + hx, oy - wy + hy, oz - wz + hz);
		int n = 4;

		if (clip != null) {
			Vec3 cn = clip.normal();
			double len = cn.length();
			if (len < 1e-9) return false;
			double nx = cn.x / len, ny = cn.y / len, nz = cn.z / len;
			Vec3 cp = clip.pos();
			double d0 = -(nx * (cp.x - cameraPos.x) + ny * (cp.y - cameraPos.y) + nz * (cp.z - cameraPos.z));
			boolean anyIn = false;
			boolean anyOut = false;
			boolean allOn = true;
			for (int i = 0; i < n; i++) {
				double d = nx * PX[i] + ny * PY[i] + nz * PZ[i] + d0;
				DIST[i] = d;
				if (Math.abs(d) > ON_PLANE_EPSILON) allOn = false;
				if (d >= 0) anyIn = true;
				else anyOut = true;
			}
			if (allOn || !anyIn) return false;
			if (anyOut) {
				n = clipPolygon(n);
				if (n < 3) return false;
			}
		}
		return project(n);
	}

	private static void setPoint(int i, double x, double y, double z) {
		PX[i] = x;
		PY[i] = y;
		PZ[i] = z;
	}

	// ------------------------------------------------------------------ other shapes

	private static boolean boxRegion(Portal portal, @Nullable Plane clip) {
		AABB box = portal.getThinBoundingBox();
		if (clip != null) {
			Vec3 cn = clip.normal();
			Vec3 cp = clip.pos();
			double x = cn.x >= 0 ? box.maxX : box.minX;
			double y = cn.y >= 0 ? box.maxY : box.minY;
			double z = cn.z >= 0 ? box.maxZ : box.minZ;
			if (cn.x * (x - cp.x) + cn.y * (y - cp.y) + cn.z * (z - cp.z) < -ON_PLANE_EPSILON) {
				return false;
			}
		}
		for (int i = 0; i < 8; i++) {
			setPoint(i,
				((i & 1) == 0 ? box.minX : box.maxX) - cameraPos.x,
				((i & 2) == 0 ? box.minY : box.maxY) - cameraPos.y,
				((i & 4) == 0 ? box.minZ : box.maxZ) - cameraPos.z
			);
		}
		// Box corners are a point cloud, not a polygon: with any corner behind the near plane the
		// projected bound is not conservative, so the parent rectangle is kept.
		toClipSpace(8);
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
		toClipSpace(n);
		boolean anyBehind = false;
		for (int i = 0; i < n; i++) {
			if (CZ[i] < -CW[i]) {
				anyBehind = true;
				break;
			}
		}
		if (anyBehind) {
			for (int i = 0; i < n; i++) DIST[i] = CZ[i] + CW[i];
			n = clipNearPlane(n);
			if (n < 3) return false;
		}
		return bounds(n);
	}

	private static void toClipSpace(int n) {
		Matrix4f m = VIEW_PROJECTION;
		for (int i = 0; i < n; i++) {
			float x = (float) PX[i], y = (float) PY[i], z = (float) PZ[i];
			CX[i] = m.m00() * x + m.m10() * y + m.m20() * z + m.m30();
			CY[i] = m.m01() * x + m.m11() * y + m.m21() * z + m.m31();
			CZ[i] = m.m02() * x + m.m12() * y + m.m22() * z + m.m32();
			CW[i] = m.m03() * x + m.m13() * y + m.m23() * z + m.m33();
		}
	}

	/** NDC bounds of the clip-space points into {@link #OUT}; false when nothing is on screen. */
	private static boolean bounds(int n) {
		int outLeft = 0, outRight = 0, outDown = 0, outUp = 0;
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		for (int i = 0; i < n; i++) {
			float w = CW[i];
			if (CX[i] < -w) outLeft++;
			if (CX[i] > w) outRight++;
			if (CY[i] < -w) outDown++;
			if (CY[i] > w) outUp++;
			if (w <= 1e-6f) continue;
			float x = CX[i] / w;
			float y = CY[i] / w;
			minX = Math.min(minX, x);
			minY = Math.min(minY, y);
			maxX = Math.max(maxX, x);
			maxY = Math.max(maxY, y);
		}
		if (outLeft == n || outRight == n || outDown == n || outUp == n || minX > maxX) return false;
		OUT[0] = Math.max(-1, minX);
		OUT[1] = Math.max(-1, minY);
		OUT[2] = Math.min(1, maxX);
		OUT[3] = Math.min(1, maxY);
		return OUT[0] < OUT[2] && OUT[1] < OUT[3];
	}

	// ------------------------------------------------------------------ polygon clipping

	/** Keeps the part of the camera-relative polygon PX/PY/PZ where {@link #DIST} {@code >= 0}. */
	private static int clipPolygon(int n) {
		int m = 0;
		for (int i = 0; i < n; i++) {
			int j = (i + 1) % n;
			double da = DIST[i], db = DIST[j];
			if (da >= 0) {
				QX[m] = PX[i];
				QY[m] = PY[i];
				QZ[m] = PZ[i];
				m++;
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

	/** Keeps the part of the clip-space polygon CX..CW in front of the near plane ({@link #DIST}). */
	private static int clipNearPlane(int n) {
		int m = 0;
		for (int i = 0; i < n; i++) {
			int j = (i + 1) % n;
			double da = DIST[i], db = DIST[j];
			if (da >= 0) {
				KX[m] = CX[i];
				KY[m] = CY[i];
				KZ[m] = CZ[i];
				KW[m] = CW[i];
				m++;
			}
			if ((da >= 0) != (db >= 0)) {
				float t = (float) (da / (da - db));
				KX[m] = CX[i] + (CX[j] - CX[i]) * t;
				KY[m] = CY[i] + (CY[j] - CY[i]) * t;
				KZ[m] = CZ[i] + (CZ[j] - CZ[i]) * t;
				KW[m] = CW[i] + (CW[j] - CW[i]) * t;
				m++;
			}
		}
		System.arraycopy(KX, 0, CX, 0, m);
		System.arraycopy(KY, 0, CY, 0, m);
		System.arraycopy(KZ, 0, CZ, 0, m);
		System.arraycopy(KW, 0, CW, 0, m);
		return m;
	}
}
