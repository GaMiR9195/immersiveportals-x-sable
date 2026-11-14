package ipl.sable.render.route;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A recorded model with the frame of each of its parts: what every level pass of the frame
 * draws of it. A pass of level L writes exactly the parts whose frame is in L, placed by the
 * frame's map, so every part exists once — on its side of every opening. Parts are kept as
 * per-primitive frame indices plus a stream of cut pieces in the recording's own arrays; nothing
 * is allocated per primitive or per vertex.
 */
final class IplRoutedModel implements IplPortalModel {

	static final IplRoutedModel EMPTY = new IplRoutedModel(List.of(), List.of(), -1, null, 0.0, 0.0, 0.0);

	private final List<IplModelRecorder.Recording> recordings;
	private final List<IplRouteFrame> frames;
	/** Index of the one frame of the whole model, or -1 when it is split. */
	private final int uniform;
	private final double ox, oy, oz;
	/** World box of the parts in each level they are drawn in. */
	private final Map<Level, AABB> bounds = new IdentityHashMap<>(2);

	private IplRoutedModel(List<IplModelRecorder.Recording> recordings, List<IplRouteFrame> frames, int uniform,
						   @Nullable AABB box, double ox, double oy, double oz) {
		this.recordings = recordings;
		this.frames = frames;
		this.uniform = uniform;
		this.ox = ox;
		this.oy = oy;
		this.oz = oz;
		if (box == null) return;
		for (IplRouteFrame f : frames) {
			AABB placed = IplPortalModel.transform(box, f.toLevel);
			bounds.merge(f.level, placed, AABB::minmax);
		}
	}

	static IplRoutedModel route(IplModelRecorder recorder, IplModelAnchor anchor, IplRouteMemory memory) {
		AABB box = recorder.bounds();
		if (box == null) return EMPTY;
		long start = IplPortalModelDiagnostics.start();
		IplRouteSolver.Result result = IplRouteSolver.solve(recorder, anchor, memory, box);
		if (IplPortalModelDiagnostics.ENABLED) {
			int primitives = 0;
			for (IplModelRecorder.Recording r : recorder.recordings()) primitives += r.units();
			IplPortalModelDiagnostics.solved(start, primitives, result.parts());
		}
		return new IplRoutedModel(recorder.recordings(), result.frames(), result.uniform(), box,
			recorder.ox, recorder.oy, recorder.oz);
	}

	/** For diagnostics: the depth of the whole model's frame, or the depths it is split over. */
	String summary() {
		if (frames.isEmpty()) return "empty";
		if (uniform >= 0) return "uncut depth=" + frames.get(uniform).depth;
		Map<Integer, Integer> byDepth = new TreeMap<>();
		for (IplRouteFrame f : frames) byDepth.merge(f.depth, 1, Integer::sum);
		return "framesByDepth=" + byDepth;
	}

	@Override
	public boolean drawsIn(Level level) {
		return bounds.containsKey(level);
	}

	@Override
	@Nullable
	public AABB boundsIn(Level level) {
		return bounds.get(level);
	}

	@Override
	public void emit(Level level, MultiBufferSource buffers, Vec3 camera) {
		if (!drawsIn(level)) return;
		if (uniform >= 0) {
			IplRouteFrame f = frames.get(uniform);
			if (f.level != level) return;
			IplModelRecorder.Placement pl = placement(f, camera);
			for (IplModelRecorder.Recording r : recordings) {
				if (r.count > 0) r.writeAll(buffers.getBuffer(r.type), pl);
			}
			return;
		}
		int n = frames.size();
		IplModelRecorder.Placement[] placements = new IplModelRecorder.Placement[n];
		boolean[] here = new boolean[n];
		for (int i = 0; i < n; i++) here[i] = frames.get(i).level == level;
		for (IplModelRecorder.Recording r : recordings) {
			if (r.count == 0) continue;
			if (r.primitiveSize == 0) {
				int fi = r.primFrame[0];
				if (fi >= 0 && here[fi]) r.writeAll(buffers.getBuffer(r.type), placement(placements, fi, camera));
				continue;
			}
			VertexConsumer out = null;
			int[] primFrame = r.primFrame;
			int[] pieces = r.pieces;
			int at = 0;
			for (int p = 0, prims = r.primitives(); p < prims; ) {
				int fi = primFrame[p];
				if (fi >= 0) {
					// A run of whole primitives in one frame.
					int end = p + 1;
					while (end < prims && primFrame[end] == fi) end++;
					if (here[fi]) {
						if (out == null) out = buffers.getBuffer(r.type);
						r.writePrimitives(out, p, end, placement(placements, fi, camera));
					}
					p = end;
					continue;
				}
				while (at < r.piecesSize && pieces[at] == p) {
					int pf = pieces[at + 1], count = pieces[at + 2];
					if (here[pf]) {
						if (out == null) out = buffers.getBuffer(r.type);
						r.writePart(out, pieces, at + 3, count, placement(placements, pf, camera));
					}
					at += 3 + count;
				}
				p++;
			}
		}
	}

	private IplModelRecorder.Placement placement(IplModelRecorder.Placement[] cache, int frame, Vec3 camera) {
		IplModelRecorder.Placement pl = cache[frame];
		if (pl == null) cache[frame] = pl = placement(frames.get(frame), camera);
		return pl;
	}

	private IplModelRecorder.Placement placement(IplRouteFrame frame, Vec3 camera) {
		return new IplModelRecorder.Placement(frame.toLevel, camera.x, camera.y, camera.z, ox, oy, oz);
	}
}
