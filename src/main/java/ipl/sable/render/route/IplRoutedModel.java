package ipl.sable.render.route;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A recorded model with the frame of each of its parts: what every level pass of the frame
 * draws of it. A pass of level L writes exactly the parts whose frame is in L, placed by the
 * frame's map, so every part exists once — on its side of every opening.
 */
final class IplRoutedModel implements IplPortalModel {

	static final IplRoutedModel EMPTY = new IplRoutedModel(List.of(), null, null, null);

	/** Openings this far from the model's geometry cannot be passed by it. */
	private static final double GEOMETRY_MARGIN = 2.0 * IplRouteRect.RIM;
	/** Most openings one model is routed through (cost bound; portal loops). */
	private static final int MAX_OPENINGS = 32;

	private final List<IplModelRecorder.Recording> recordings;
	/** The one frame of the whole model, or null when it is split into {@link #parts}. */
	@Nullable private final IplRouteFrame uniform;
	/** Parts in recording order; null when the whole model is in {@link #uniform}. */
	@Nullable private final List<IplRouteSolver.Part> parts;
	/** World box of the parts in each level they are drawn in. */
	private final Map<Level, AABB> bounds = new IdentityHashMap<>(2);

	private IplRoutedModel(List<IplModelRecorder.Recording> recordings, @Nullable IplRouteFrame uniform,
						   @Nullable List<IplRouteSolver.Part> parts, @Nullable AABB box) {
		this.recordings = recordings;
		this.uniform = uniform;
		this.parts = parts;
		if (box == null) return;
		Set<IplRouteFrame> frames = Collections.newSetFromMap(new IdentityHashMap<>());
		if (uniform != null) frames.add(uniform);
		if (parts != null) for (IplRouteSolver.Part p : parts) frames.add(p.frame);
		for (IplRouteFrame f : frames) {
			AABB placed = IplPortalModel.transform(box, f.toLevel);
			bounds.merge(f.level, placed, AABB::minmax);
		}
	}

	static IplRoutedModel route(IplModelRecorder recorder, IplModelAnchor anchor) {
		AABB box = recorder.bounds();
		if (box == null) return EMPTY;
		List<IplModelRecorder.Recording> recordings = recorder.recordings();
		List<IplRouteOpening> openings = openingsNear(anchor.frame(), box);
		if (openings.isEmpty()) return new IplRoutedModel(recordings, anchor.frame(), null, box);
		long start = IplPortalModelDiagnostics.start();
		IplRouteSolver.Result result = IplRouteSolver.solve(recordings, openings, anchor.point(), anchor.frame());
		if (IplPortalModelDiagnostics.ENABLED) {
			int primitives = 0;
			for (IplModelRecorder.Recording r : recordings) primitives += r.primitives();
			IplPortalModelDiagnostics.solved(start, primitives, result.parts() == null ? 1 : result.parts().size());
		}
		return new IplRoutedModel(recordings, result.uniform(), result.parts(), box);
	}

	/**
	 * The openings the geometry in {@code box} may pass: those of the anchor's frame and of its
	 * ancestors, and of every frame those lead to, as long as they come near the geometry.
	 */
	private static List<IplRouteOpening> openingsNear(IplRouteFrame anchorFrame, AABB box) {
		List<IplRouteOpening> out = new ArrayList<>();
		Set<IplRouteFrame> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<IplRouteFrame> queue = new ArrayDeque<>();
		for (IplRouteFrame f = anchorFrame; f != null; f = f.parent) queue.add(f);
		while (!queue.isEmpty() && out.size() < MAX_OPENINGS) {
			IplRouteFrame frame = queue.poll();
			if (!visited.add(frame)) continue;
			for (IplOpening opening : IplOpenings.of(frame.level)) {
				IplRouteRect rect = frame.rectOf(opening);
				if (!rect.near(box, GEOMETRY_MARGIN)) continue;
				out.add(new IplRouteOpening(frame, opening, rect));
				IplRouteFrame next = frame.through(opening);
				if (next != null && !visited.contains(next)) queue.add(next);
				if (out.size() == MAX_OPENINGS) break;
			}
		}
		return out;
	}

	/** For diagnostics: the depth of the whole model's frame, or how many parts each frame depth holds. */
	String summary() {
		if (uniform != null) return "uncut depth=" + uniform.depth;
		if (parts == null) return "empty";
		Map<Integer, Integer> byDepth = new java.util.TreeMap<>();
		for (IplRouteSolver.Part p : parts) byDepth.merge(p.frame.depth, 1, Integer::sum);
		return "partsByDepth=" + byDepth;
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
		if (uniform != null) {
			if (uniform.level != level) return;
			IplVertex.Placement placement = placement(uniform, camera);
			for (IplModelRecorder.Recording recording : recordings) {
				if (!recording.vertices.isEmpty()) recording.emitAll(buffers.getBuffer(recording.type), placement);
			}
			return;
		}
		if (parts == null) return;
		Map<IplRouteFrame, IplVertex.Placement> placements = new IdentityHashMap<>(2);
		int next = 0;
		for (int r = 0; r < recordings.size(); r++) {
			IplModelRecorder.Recording recording = recordings.get(r);
			VertexConsumer out = null;
			for (; next < parts.size() && parts.get(next).recording == r; next++) {
				IplRouteSolver.Part part = parts.get(next);
				if (part.frame.level != level) continue;
				if (out == null) out = buffers.getBuffer(recording.type);
				IplVertex.Placement placement = placements.computeIfAbsent(part.frame, f -> placement(f, camera));
				if (part.polygon == null) recording.emitAll(out, placement);
				else recording.emit(out, part.polygon, placement);
			}
		}
	}

	private static IplVertex.Placement placement(IplRouteFrame frame, Vec3 camera) {
		return new IplVertex.Placement(new Matrix4d().translation(-camera.x, -camera.y, -camera.z).mul(frame.toLevel));
	}
}
