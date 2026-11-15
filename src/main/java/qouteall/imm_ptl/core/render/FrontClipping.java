package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.vertex.PoseStack;
import ipl.sable.render.clip.IplClipPlanes;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.q_misc_util.my_util.Plane;

/**
 * The portal clips of {@link IplClipPlanes}.
 *
 * <p>Inside a portal pass the front clip ({@link IplClipPlanes#PORTAL}) is a property of the
 * WHOLE pass: from the start of the level render to its end (the after-level stage included)
 * every draw is cut at the rendering portal's destination plane, whichever renderer issues it
 * and whenever its buffers are flushed. A caller can only NUDGE that plane (terrain moves it
 * back by {@link #ADJUSTMENT} so world-wrapping portals do not z-fight); any other plane a
 * caller asks for goes to {@link IplClipPlanes#OBJECT} and is applied together with the pass
 * clip. So an entity crossing a second portal, or the far-side image of an entity, is cut by
 * both planes and can never pull geometry from behind the destination plane into the view.
 */
public class FrontClipping {
	private static final Minecraft client = Minecraft.getInstance();

	public static final double ADJUSTMENT = 0.01;

	/** Start of a level render: the pass default (portal plane inside a portal pass, else none). */
	public static void beginLevelPass() {
		restorePassDefault();
		IplClipPlanes.setPlane(IplClipPlanes.OBJECT, null);
	}

	/** Ends a caller's cut: back to the pass default, object cut removed. */
	public static void disableClipping() {
		restorePassDefault();
		IplClipPlanes.setPlane(IplClipPlanes.OBJECT, null);
	}

	public static void updateInnerClipping(PoseStack matrixStack) {
		disableClipping();
	}

	public static void updateInnerClipping(Matrix4f modelView) {
		disableClipping();
	}

	public static boolean isClippingEnabled() {
		return IplClipPlanes.isActive(IplClipPlanes.PORTAL) || IplClipPlanes.isActive(IplClipPlanes.OBJECT);
	}

	/**
	 * Cuts the following draws at {@code clipping} (normal towards the kept side). The pass's own
	 * plane only changes the pass clip's adjustment; any other plane is an object cut, applied in
	 * addition to the pass clip. {@code null} removes the object cut.
	 */
	public static void setupInnerClipping(@Nullable Plane clipping, Matrix4f modelView, double adjustment) {
		if (!IPCGlobal.useFrontClipping) return;
		if (clipping == null) {
			IplClipPlanes.setPlane(IplClipPlanes.OBJECT, null);
			return;
		}
		Plane pass = passPlane();
		if (pass != null && isSamePlane(pass, clipping)) {
			IplClipPlanes.setPlane(IplClipPlanes.PORTAL, innerEquation(pass, adjustment));
			return;
		}
		IplClipPlanes.setPlane(IplClipPlanes.OBJECT, innerEquation(clipping, adjustment));
	}

	public static void setupOuterClipping(PoseStack matrixStack, Portal portal) {
		if (!IPCGlobal.useFrontClipping) return;
		IplClipPlanes.setPlane(IplClipPlanes.OBJECT, outerEquation(portal));
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

	private static boolean isSamePlane(Plane a, Plane b) {
		Vec3 na = a.normal();
		Vec3 nb = b.normal();
		double la = na.length();
		double lb = nb.length();
		if (la < 1e-9 || lb < 1e-9) return false;
		if (na.dot(nb) / (la * lb) < 0.999999) return false;
		return Math.abs(na.dot(b.pos().subtract(a.pos())) / la) < 1e-5;
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
		Vec3 portalPos = outerClipping.pos().subtract(client.gameRenderer.getMainCamera().getPosition());
		return new double[]{planeNormal.x, planeNormal.y, planeNormal.z, -planeNormal.dot(portalPos)};
	}
}
