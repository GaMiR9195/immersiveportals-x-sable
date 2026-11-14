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
 * The portal clip ({@link IplClipPlanes#PORTAL}).
 *
 * <p>Inside a portal pass the clip is a property of the WHOLE pass: from the start of the level
 * render to its end every draw is cut at the rendering portal's plane, whichever renderer issues
 * it and whenever its buffers are flushed (terrain, entities, block entities, sign text, Flywheel,
 * Veil, Sable sub-levels, particles, weather, clouds). Only screen-space draws suspend it
 * ({@link IplClipPlanes#suppress}). A caller may replace the plane for its own draws (terrain
 * with a small z-fight adjustment, an entity crossing another portal); {@link #disableClipping}
 * ends that override and returns to the pass default, it never leaves a portal pass unclipped.
 */
public class FrontClipping {
	private static final Minecraft client = Minecraft.getInstance();

	public static final double ADJUSTMENT = 0.01;

	/** Start of a level render: the pass default (portal plane inside a portal pass, else none). */
	public static void beginLevelPass() {
		restorePassDefault();
	}

	/** Ends a caller's override: back to the pass default. */
	public static void disableClipping() {
		restorePassDefault();
	}

	public static void updateInnerClipping(PoseStack matrixStack) {
		restorePassDefault();
	}

	public static void updateInnerClipping(Matrix4f modelView) {
		restorePassDefault();
	}

	public static boolean isClippingEnabled() {
		return IplClipPlanes.isActive(IplClipPlanes.PORTAL);
	}

	/** Cuts the following draws at {@code clipping} (null: none); the normal points to the kept side. */
	public static void setupInnerClipping(@Nullable Plane clipping, Matrix4f modelView, double adjustment) {
		if (!IPCGlobal.useFrontClipping) return;
		setPortalEquation(clipping == null ? null : innerEquation(clipping, adjustment));
	}

	public static void setupOuterClipping(PoseStack matrixStack, Portal portal) {
		if (!IPCGlobal.useFrontClipping) return;
		setPortalEquation(outerEquation(portal));
	}

	private static void restorePassDefault() {
		Plane plane = PortalRendering.isRendering() && IPCGlobal.useFrontClipping
			? PortalRendering.getActiveClippingPlane() : null;
		setPortalEquation(plane == null ? null : innerEquation(plane, 0));
	}

	private static void setPortalEquation(double @Nullable [] equation) {
		IplClipPlanes.setPlane(IplClipPlanes.PORTAL, equation);
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
