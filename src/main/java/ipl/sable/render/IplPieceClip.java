package ipl.sable.render;

import ipl.sable.render.clip.IplClipPlanes;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.CHelper;
import qouteall.q_misc_util.my_util.Plane;

/**
 * The aperture cuts of the Sable piece being drawn: world planes whose normal points to the kept
 * side ({@link IplPieceRender#keepPlane}), held in the piece slots of {@link IplClipPlanes}.
 */
public final class IplPieceClip {

	private IplPieceClip() {}

	/** Cuts the following draws at every non-null plane (at most {@link IplClipPlanes#PIECE_CUTS}). */
	public static void set(@Nullable Plane... planes) {
		Vec3 camera = CHelper.getCurrentCameraPos();
		int slot = IplClipPlanes.FIRST_PIECE_CUT;
		for (Plane plane : planes) {
			if (plane == null) continue;
			if (slot >= IplClipPlanes.SLOTS) {
				throw new IllegalArgumentException("more piece cuts than clip slots: " + planes.length);
			}
			IplClipPlanes.setPlane(slot++, equation(plane, camera));
		}
		for (; slot < IplClipPlanes.SLOTS; slot++) IplClipPlanes.setPlane(slot, null);
	}

	public static void clear() {
		IplClipPlanes.clearPieceCuts();
	}

	/** {@code (n, -n · (p - camera))}: the plane in camera-relative world space. */
	private static double[] equation(Plane plane, Vec3 camera) {
		Vec3 n = plane.normal();
		return new double[]{n.x, n.y, n.z, -n.dot(plane.pos().subtract(camera))};
	}
}
