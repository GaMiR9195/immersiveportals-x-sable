package ipl.sable.render.route;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * What a routed model keeps between render frames. No part remembers a frame: every frame the
 * route is walked again from the block that holds the model along the model itself
 * ({@link IplRouteSolver}), so a part is always where its connection to the holder puts it and
 * nothing that went into a portal can stay behind when the holder pulls it back or carries it
 * on. What is kept is the recorder, the model's touch graph ({@link IplRouteTopology}) and the
 * recording layout that graph was built for.
 *
 * <p>{@link #holding}: some part is drawn in another frame than the holder's, so the model stays
 * routed even when its reported bounds no longer come near an opening. Render thread only.
 */
final class IplRouteMemory {

	/** The recorder this model writes into, reused every frame. */
	final IplModelRecorder recorder = new IplModelRecorder();
	/** Which primitives touch; kept while the layout is unchanged. */
	final IplRouteTopology topology = new IplRouteTopology();

	@Nullable Level level;
	long frame = Long.MIN_VALUE;
	/** Some part is in another frame than the holder's. */
	boolean holding;

	// Layout of the last recording (render types and units per recording).
	int recordings;
	RenderType[] types = new RenderType[0];
	int[] unitCounts = new int[0];

	// Per-solve buffers: unit centres (route space) and radii (measured when the graph is rebuilt).
	double[] centroids = new double[0];
	float[] radii = new float[0];

	/** Forgets everything (another level). */
	void reset() {
		recordings = 0;
		holding = false;
		topology.invalidate();
	}

	void ensure(int units) {
		if (centroids.length < units * 3) centroids = new double[Math.max(units * 3, centroids.length * 2)];
		if (radii.length < units) radii = new float[Math.max(units, radii.length * 2)];
	}
}
