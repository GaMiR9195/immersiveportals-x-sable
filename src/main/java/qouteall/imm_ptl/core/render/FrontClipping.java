package qouteall.imm_ptl.core.render;

import ipl.sable.render.clip.IplClipPlanes;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.q_misc_util.my_util.Plane;

import java.util.List;
import java.util.Objects;

/**
 * The portal clips of {@link IplClipPlanes}.
 *
 * <p>Inside a portal pass the front clip ({@link IplClipPlanes#PORTAL}) is a property of the
 * WHOLE pass: from the start of the level render to its end (the after-level stage included)
 * every draw is cut at the rendering portal's destination plane, whichever renderer issues it
 * and whenever its buffers are flushed. A caller can only NUDGE that plane (terrain moves it
 * back by {@link #ADJUSTMENT} so world-wrapping portals do not z-fight); any other plane a
 * caller asks for is an object cut ({@link IplClipPlanes#OBJECT}) applied together with the pass
 * clip. So an entity crossing other portals, or the far-side image of an entity, is cut by
 * every plane and can never pull geometry from behind the destination plane into the view.
 */
public class FrontClipping {
	public static final double ADJUSTMENT = 0.01;
	/** Parallel planes this close are one plane. */
	private static final double SAME_PLANE_COS = 0.999999;
	private static final double SAME_PLANE_DISTANCE = 1e-5;
	/** Sub-level apertures come from the body's pose, so they match their portal only approximately. */
	private static final double SAME_OPENING_COS = 0.999;
	private static final double SAME_OPENING_DISTANCE = 0.05;

	/** Start of a level render: the pass default (portal plane inside a portal pass, else none). */
	public static void beginLevelPass() {
		disableClipping();
	}

	/** Ends a caller's cut: back to the pass default, object cuts removed. */
	public static void disableClipping() {
		restorePassDefault();
		IplClipPlanes.setObjectCuts();
	}

	public static boolean isClippingEnabled() {
		return IplClipPlanes.isActive(IplClipPlanes.PORTAL) || IplClipPlanes.isObjectCutActive();
	}

	/**
	 * Cuts the following draws at {@code clipping} (normal towards the kept side). The pass's own
	 * plane only changes the pass clip's adjustment; any other plane is an object cut, applied in
	 * addition to the pass clip. {@code null} removes the object cuts.
	 */
	public static void setupInnerClipping(@Nullable Plane clipping, Matrix4f modelView, double adjustment) {
		if (!IPCGlobal.useFrontClipping) return;
		if (clipping == null) {
			IplClipPlanes.setObjectCuts();
			return;
		}
		Plane pass = passPlane();
		if (pass != null && isSamePlane(pass, clipping, SAME_PLANE_COS, SAME_PLANE_DISTANCE)) {
			IplClipPlanes.setPlane(IplClipPlanes.PORTAL, innerEquation(pass, adjustment));
			return;
		}
		IplClipPlanes.setObjectCuts(innerEquation(clipping, adjustment));
	}

	/**
	 * Clips the far-side image of an entity crossing {@code portal}. Through that same portal the
	 * image is drawn whole: its part in front of the destination plane is the entity's own near
	 * part, hidden wherever the view still shows that part in front of the portal, and it fills
	 * the pixels next to the plane that the portal area takes over, so the cut has no seam. Through
	 * any other portal the image is cut at the colliding portal's destination plane.
	 */
	public static void setupProjectionClipping(Portal portal, Matrix4f modelView) {
		if (!IPCGlobal.useFrontClipping) return;
		Plane clipping = portal.getInnerClipping();
		if (isPassOpening(clipping)) {
			IplClipPlanes.setPlane(IplClipPlanes.PORTAL, null);
			IplClipPlanes.setObjectCuts();
			return;
		}
		setupInnerClipping(clipping, modelView, 0);
	}

	/** Cuts the following draws at the outer side of every opening in {@code portals}. */
	public static void setupOuterClipping(List<Portal> portals) {
		if (!IPCGlobal.useFrontClipping) return;
		IplClipPlanes.setObjectCuts(
			portals.stream().map(FrontClipping::outerEquation).filter(Objects::nonNull).toArray(double[][]::new)
		);
	}

	/**
	 * Cuts an entity crossing {@code portals} at their outer sides. A pass that looks out of one of
	 * them from its far side draws the entity whole, like its far-side image through the portal it
	 * crosses ({@link #setupProjectionClipping}).
	 */
	public static void setupCrossingClipping(List<Portal> portals) {
		if (!IPCGlobal.useFrontClipping) return;
		List<Portal> cutting = portals.stream().filter(p -> !looksOutOf(p)).toList();
		if (cutting.size() < portals.size()) {
			IplClipPlanes.setPlane(IplClipPlanes.PORTAL, null);
		}
		setupOuterClipping(cutting);
	}

	/** Whether the pass looks out of {@code portal} from its far side: its front clip is the portal's own plane. */
	public static boolean looksOutOf(Portal portal) {
		return isPassOpening(portal.getPortalShape().getOuterClipping(portal.getThisSideState()));
	}

	/**
	 * Whether {@code plane} is the opening the pass looks out of. Geometry crossing it is drawn
	 * whole in the pass: with shader packs the portal area takes over the pixels right in front of
	 * the opening (its stencil is pulled toward the camera against TAA jitter), and those pixels
	 * must show that geometry rather than what lies behind it.
	 */
	public static boolean isPassOpening(@Nullable Plane plane) {
		Plane pass = passPlane();
		return plane != null && pass != null
			&& isSamePlane(pass, plane, SAME_OPENING_COS, SAME_OPENING_DISTANCE);
	}

	/** Runs {@code draw} without the pass's front clip; the clip is restored afterwards. */
	public static void withoutPassClip(Runnable draw) {
		double[] saved = IplClipPlanes.plane(IplClipPlanes.PORTAL);
		IplClipPlanes.setPlane(IplClipPlanes.PORTAL, null);
		try {
			draw.run();
		} finally {
			IplClipPlanes.setPlane(IplClipPlanes.PORTAL, saved);
		}
	}

	/** The front clip plane of the pass being drawn, or null outside portal passes. */
	@Nullable
	public static Plane passPlane() {
		return PortalRendering.isRendering() && IPCGlobal.useFrontClipping
			? PortalRendering.getActiveClippingPlane() : null;
	}

	private static void restorePassDefault() {
		Plane plane = passPlane();
		IplClipPlanes.setPlane(IplClipPlanes.PORTAL, plane == null ? null : innerEquation(plane, 0));
	}

	private static boolean isSamePlane(Plane a, Plane b, double minCos, double maxDistance) {
		Vec3 na = a.normal();
		Vec3 nb = b.normal();
		double la = na.length();
		double lb = nb.length();
		if (la < 1e-9 || lb < 1e-9) return false;
		if (na.dot(nb) / (la * lb) < minCos) return false;
		return Math.abs(na.dot(b.pos().subtract(a.pos())) / la) < maxDistance;
	}

	private static double[] innerEquation(Plane clipping, double correction) {
		Vec3 planeNormal = clipping.normal();
		Vec3 portalPos = clipping.pos()
			.add(planeNormal.scale(correction))
			.subtract(CHelper.getCurrentCameraPos());
		// planeNormal · p + c >= 0 on the kept side
		return new double[]{planeNormal.x, planeNormal.y, planeNormal.z, -planeNormal.dot(portalPos)};
	}

	private static double @Nullable [] outerEquation(Portal portal) {
		@Nullable Plane outerClipping = portal.getPortalShape().getOuterClipping(portal.getThisSideState());
		if (outerClipping == null) return null;
		Vec3 planeNormal = outerClipping.normal();
		Vec3 portalPos = outerClipping.pos().subtract(CHelper.getCurrentCameraPos());
		return new double[]{planeNormal.x, planeNormal.y, planeNormal.z, -planeNormal.dot(portalPos)};
	}
}
