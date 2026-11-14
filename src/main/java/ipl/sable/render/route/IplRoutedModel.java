package ipl.sable.render.route;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Vector3d;

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
	/** Geometry this close to a plane counts as on it (neither side). */
	private static final double SIDE_EPSILON = 1.0e-6;
	/** Most openings one model is routed through (cost bound; portal loops). */
	private static final int MAX_OPENINGS = 32;

	private final List<IplModelRecorder.Recording> recordings;
	@Nullable private final IplRouteFrame anchorFrame;
	/** Parts in recording order; null when the whole model is in {@link #anchorFrame}. */
	@Nullable private final List<IplPrimitiveCutter.Part> parts;
	/** World box of the parts in each level they are drawn in. */
	private final Map<Level, AABB> bounds = new IdentityHashMap<>(2);

	private IplRoutedModel(List<IplModelRecorder.Recording> recordings, @Nullable IplRouteFrame anchorFrame,
						   @Nullable List<IplPrimitiveCutter.Part> parts, @Nullable AABB box) {
		this.recordings = recordings;
		this.anchorFrame = anchorFrame;
		this.parts = parts;
		if (anchorFrame == null || box == null) return;
		Set<IplRouteFrame> frames = Collections.newSetFromMap(new IdentityHashMap<>());
		for (IplModelRecorder.Recording r : recordings) {
			if (parts == null || r.primitiveSize == 0) frames.add(anchorFrame);
		}
		if (parts != null) for (IplPrimitiveCutter.Part p : parts) frames.add(p.frame);
		for (IplRouteFrame f : frames) {
			AABB placed = IplPortalModel.transform(box, f.toLevel);
			bounds.merge(f.level, placed, AABB::minmax);
		}
	}


	static IplRoutedModel route(IplModelRecorder recorder, IplModelAnchor anchor) {
		AABB box = recorder.bounds();
		if (box == null) return EMPTY;
		List<IplModelRecorder.Recording> recordings = recorder.recordings();
		List<IplRouteOpening> openings = crossed(openingsNear(anchor.frame(), box), recordings, anchor.point());
		if (openings.isEmpty()) return new IplRoutedModel(recordings, anchor.frame(), null, box);
		long start = IplPortalModelDiagnostics.start();
		List<IplPrimitiveCutter.Part> parts = IplRouteSolver.solve(recordings, openings, anchor.point(), anchor.frame());
		int primitives = 0;
		for (IplModelRecorder.Recording r : recordings) primitives += r.primitives();
		IplPortalModelDiagnostics.solved(start, primitives, parts.size());
		return new IplRoutedModel(recordings, anchor.frame(), parts, box);
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

	/**
	 * Of {@code near}, the openings whose plane has the geometry (or the anchor) on both sides:
	 * no path can pass any other. Most models near an opening pass none and are never cut.
	 */
	private static List<IplRouteOpening> crossed(List<IplRouteOpening> near, List<IplModelRecorder.Recording> recordings,
												 Vector3d anchor) {
		if (near.isEmpty()) return near;
		List<IplRouteOpening> out = new ArrayList<>(near.size());
		for (IplRouteOpening o : near) {
			IplRouteRect rect = o.rect();
			double anchorSide = rect.side(anchor.x, anchor.y, anchor.z);
			boolean front = anchorSide > SIDE_EPSILON, back = anchorSide < -SIDE_EPSILON;
			for (IplModelRecorder.Recording r : recordings) {
				for (IplVertex v : r.vertices) {
					double d = rect.side(v);
					front |= d > SIDE_EPSILON;
					back |= d < -SIDE_EPSILON;
					if (front && back) break;
				}
				if (front && back) break;
			}
			if (front && back) out.add(o);
		}
		return out;
	}

	/** For diagnostics: the anchor's frame depth and how many parts each frame depth holds. */
	String summary() {
		if (anchorFrame == null) return "empty";
		if (parts == null) return "anchorDepth=" + anchorFrame.depth + " uncut";
		Map<Integer, Integer> byDepth = new java.util.TreeMap<>();
		for (IplPrimitiveCutter.Part p : parts) byDepth.merge(p.frame.depth, 1, Integer::sum);
		return "anchorDepth=" + anchorFrame.depth + " partsByDepth=" + byDepth;
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
		Map<IplRouteFrame, IplVertex.Placement> placements = new IdentityHashMap<>(2);
		int next = 0;
		for (int r = 0; r < recordings.size(); r++) {
			IplModelRecorder.Recording recording = recordings.get(r);
			if (parts == null || recording.primitiveSize == 0) {
				if (anchorFrame.level == level && !recording.vertices.isEmpty()) {
					recording.emitAll(buffers.getBuffer(recording.type), placement(placements, anchorFrame, camera));
				}
				continue;
			}
			VertexConsumer out = null;
			for (; next < parts.size() && parts.get(next).recording == r; next++) {
				IplPrimitiveCutter.Part part = parts.get(next);
				if (part.frame.level != level) continue;
				if (out == null) out = buffers.getBuffer(recording.type);
				recording.emit(out, part.polygon, placement(placements, part.frame, camera));
			}
		}
	}

	private static IplVertex.Placement placement(Map<IplRouteFrame, IplVertex.Placement> cache, IplRouteFrame frame, Vec3 camera) {
		return cache.computeIfAbsent(frame, f ->
			new IplVertex.Placement(new Matrix4d().translation(-camera.x, -camera.y, -camera.z).mul(f.toLevel)));
	}
}
