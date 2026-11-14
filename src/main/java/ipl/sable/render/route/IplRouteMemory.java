package ipl.sable.render.route;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What a routed model remembers between render frames — its <em>route session</em>. Every
 * primitive (a strip or fan recording counts as one) carries a label: the frame it is in, kept
 * as a {@link IplFrameKey}. A label changes only when the primitive's centre actually passes an
 * opening's finite area between two frames, measured in that opening's own coordinates, exactly
 * like the voxel labels of a body. So material that went through stays through wherever it goes
 * afterwards (sideways behind the portal, around its rim, far away), and material that went
 * around the rim never counts as passed.
 *
 * <p>The session is held as long as any label differs from the frame of the block that holds the
 * model: the model stays routed however far it gets from the opening. A label whose portal path
 * cannot be resolved (portal unloaded for a moment, level not loaded) is kept for
 * {@link #MISSING_HOLD_NANOS} and drawn in the deepest frame that still resolves meanwhile. When
 * the holder teleports with its body (a flip), every label is re-expressed from the holder's new
 * place, so nothing jumps. Render thread only.
 */
final class IplRouteMemory {

	/** How long a label whose path does not resolve is kept before it falls back for good. */
	static final long MISSING_HOLD_NANOS = 10_000_000_000L;

	/** One face placed in route space, identified across frames. */
	record RectKey(IplFrameKey frame, UUID opening) {}

	/** The recorder this model writes into, reused every frame. */
	final IplModelRecorder recorder = new IplModelRecorder();

	@Nullable Level level;
	long frame = Long.MIN_VALUE;
	/** Some label differs from the holder's frame: the model stays routed wherever it is. */
	boolean holding;

	// The last solve: the layout of the recording, per unit its centre (route space) and label.
	int units;
	RenderType[] types = new RenderType[0];
	int[] unitCounts = new int[0];
	int recordings;
	double[] centroids = new double[0];
	IplFrameKey[] labels = new IplFrameKey[0];

	// Buffers of the solve being written; swapped with the above at the end of a solve.
	double[] nextCentroids = new double[0];
	IplFrameKey[] nextLabels = new IplFrameKey[0];

	/** Faces as they were placed in the last solve (sweeps are measured against them). */
	Map<RectKey, IplRouteRect> rects = new HashMap<>();
	Map<RectKey, IplRouteRect> nextRects = new HashMap<>();
	/** When a label first failed to resolve. */
	final Map<IplFrameKey, Long> missingSince = new HashMap<>(2);

	@Nullable IplFrameKey anchorKey;
	double ax, ay, az;
	@Nullable AABB box;

	/** Forgets everything (another level, or the layout cannot be trusted). */
	void reset() {
		units = 0;
		recordings = 0;
		holding = false;
		anchorKey = null;
		box = null;
		rects.clear();
		missingSince.clear();
	}

	void ensureNext(int units) {
		if (nextCentroids.length < units * 3) nextCentroids = new double[Math.max(units * 3, nextCentroids.length * 2)];
		if (nextLabels.length < units) nextLabels = new IplFrameKey[Math.max(units, nextLabels.length * 2)];
	}

	/** Makes the solve just written the remembered one. */
	void swap(int units) {
		double[] c = centroids;
		centroids = nextCentroids;
		nextCentroids = c;
		IplFrameKey[] l = labels;
		labels = nextLabels;
		nextLabels = l;
		for (int i = units; i < labels.length && labels[i] != null; i++) labels[i] = null;
		this.units = units;
		Map<RectKey, IplRouteRect> r = rects;
		rects = nextRects;
		nextRects = r;
		nextRects.clear();
	}
}
