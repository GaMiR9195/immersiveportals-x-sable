package qouteall.imm_ptl.core.render;

import net.minecraft.client.Camera;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import qouteall.imm_ptl.core.mixin.client.render.IECameraOrientation;

import java.util.ArrayDeque;

/**
 * The view of one world render issued by {@code GameRenderer.renderLevel}, from the level render
 * to the end of the after-level stage.
 *
 * <p>In a portal pass the portal's rotation is part of the model-view matrix only; the camera keeps
 * the player's orientation. Everything that faces the camera (particles, name tags, beams, the
 * staff anchor, any mod's billboard reading {@code Camera.rotation()}) would then face the
 * player's real direction instead of the direction actually looked along in the rendered world.
 * For the duration of the view the camera's orientation is set to the effective one, taken from
 * the model-view matrix (a mirror is folded into a proper rotation), and restored afterwards.
 * Views whose model-view is the camera's own rotation (the normal view) are left untouched.
 */
public final class PortalPassView {
	private static final class Frame {
		final int depth;
		@Nullable Camera camera;
		final Quaternionf savedRotation = new Quaternionf();
		final Vector3f savedForwards = new Vector3f();
		final Vector3f savedUp = new Vector3f();
		final Vector3f savedLeft = new Vector3f();
		float savedXRot;
		float savedYRot;

		Frame(int depth) {
			this.depth = depth;
		}
	}

	private static final ArrayDeque<Frame> FRAMES = new ArrayDeque<>();
	private static int gameRenderDepth;

	// scratch, render thread only
	private static final Matrix3f VIEW = new Matrix3f();
	private static final Quaternionf EFFECTIVE = new Quaternionf();
	private static final Quaternionf DELTA = new Quaternionf();

	private PortalPassView() {}

	/** Start of a frame: a view left open by an exception in the last frame is closed. */
	public static void onFrameStart() {
		while (!FRAMES.isEmpty()) restore(FRAMES.pop());
		gameRenderDepth = 0;
	}

	public static void onGameRenderLevelBegin() {
		gameRenderDepth++;
	}

	/** End of {@code GameRenderer.renderLevel}: closes a view whose after-level stage did not run. */
	public static void onGameRenderLevelEnd() {
		while (!FRAMES.isEmpty() && FRAMES.peek().depth >= gameRenderDepth) {
			restore(FRAMES.pop());
		}
		if (gameRenderDepth > 0) gameRenderDepth--;
	}

	/** Opens the view right before the level render. */
	public static void open(Camera camera, Matrix4f modelView) {
		Frame frame = new Frame(gameRenderDepth);
		FRAMES.push(frame);
		orientCamera(frame, camera, modelView);
	}

	/** Closes the innermost view (after its after-level stage). */
	public static void close() {
		if (!FRAMES.isEmpty() && FRAMES.peek().depth >= gameRenderDepth) {
			restore(FRAMES.pop());
		}
	}

	private static void restore(Frame frame) {
		if (frame.camera == null) return;
		IECameraOrientation c = (IECameraOrientation) frame.camera;
		c.ip_rotation().set(frame.savedRotation);
		c.ip_forwards().set(frame.savedForwards);
		c.ip_up().set(frame.savedUp);
		c.ip_left().set(frame.savedLeft);
		c.ip_setXRot(frame.savedXRot);
		c.ip_setYRot(frame.savedYRot);
	}

	private static void orientCamera(Frame frame, Camera camera, Matrix4f modelView) {
		Matrix3f view = modelView.get3x3(VIEW);
		if (view.determinant() < 0) {
			// A mirror: fold the reflection into the view's x axis. A billboard keeps facing the
			// camera, only its texture is mirrored, as everything else in the mirror is.
			view.m00(-view.m00()).m10(-view.m10()).m20(-view.m20());
		}
		Quaternionf effective = view.getNormalizedRotation(EFFECTIVE).conjugate();
		if (!Float.isFinite(effective.w) || !Float.isFinite(effective.x)) return;

		IECameraOrientation c = (IECameraOrientation) camera;
		Quaternionf current = c.ip_rotation();
		if (Math.abs(current.dot(effective)) > 0.9999995f) return;

		frame.camera = camera;
		frame.savedRotation.set(current);
		frame.savedForwards.set(c.ip_forwards());
		frame.savedUp.set(c.ip_up());
		frame.savedLeft.set(c.ip_left());
		frame.savedXRot = c.ip_getXRot();
		frame.savedYRot = c.ip_getYRot();

		Quaternionf delta = DELTA.set(current).conjugate().premul(effective);
		delta.transform(c.ip_forwards());
		delta.transform(c.ip_up());
		delta.transform(c.ip_left());
		current.set(effective);

		Vector3f f = c.ip_forwards();
		c.ip_setXRot((float) Math.toDegrees(-Math.asin(Math.max(-1.0, Math.min(1.0, f.y)))));
		c.ip_setYRot((float) Math.toDegrees(Math.atan2(-f.x, f.z)));
	}
}
