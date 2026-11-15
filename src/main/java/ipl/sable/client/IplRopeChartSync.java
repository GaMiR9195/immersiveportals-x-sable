package ipl.sable.client;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;
import dev.simulated_team.simulated.content.blocks.rope.strand.client.ClientLevelRopeManager;
import dev.simulated_team.simulated.content.blocks.rope.strand.client.ClientRopePoint;
import dev.simulated_team.simulated.content.blocks.rope.strand.client.ClientRopeStrand;
import org.joml.Vector3d;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Keeps Simulated's client rope strands in the coordinate chart of the bodies they hang on
 * across a same-dimension flip, the way {@link IplParentDimSync} keeps the bodies themselves.
 *
 * <p>The server moves the points of a crossing group's ropes in the flip tick. A client strand
 * interpolates between snapshots on the shared Sable clock, so left alone the delayed strand
 * would interpolate from the old chart to the new one and fly across the portal distance while
 * its bodies jump in one tick. Every rope snapshot is therefore kept in the chart the client
 * currently SHOWS for the strand's holder body: points received while that body's flip is still
 * pending are mapped back on receipt, and when the flip is applied every strand held on the body
 * (current, previous and buffered positions) is rebased in the same tick as the body.
 *
 * <p>Ropes of a crossing group always hang on a group member (a rope to anything outside the
 * group anchors it, see {@code IplTransitController}). Client main thread only.
 */
public final class IplRopeChartSync {

	/** Rope id → id of the body carrying its holder, learned from the rope packets. */
	private static final Map<UUID, UUID> HOLDER_BODIES = new HashMap<>();

	private IplRopeChartSync() {}

	/** Points of rope {@code ropeId} received for {@code holder}, about to become snapshots at {@code tick}. */
	public static void onReceive(RopeStrandHolderBehavior holder, UUID ropeId, int tick, List<Vector3d> points) {
		SubLevel body = holder.blockEntity == null ? null : Sable.HELPER.getContaining(holder.blockEntity);
		if (body == null) {
			HOLDER_BODIES.remove(ropeId);
			return;
		}
		HOLDER_BODIES.put(ropeId, body.getUniqueId());
		IplParentDimSync.mapPendingDestinationPointsBack(body.getUniqueId(), tick, points);
	}

	/** {@code body} entered its destination chart: the strands held on it follow, history included. */
	static void rebase(ClientSubLevel body, Consumer<Vector3d> map) {
		UUID bodyId = body.getUniqueId();
		Set<Vector3d> moved = Collections.newSetFromMap(new IdentityHashMap<>());
		for (ClientRopeStrand strand : ClientLevelRopeManager.getOrCreate(body.getLevel()).getAllStrands()) {
			if (!bodyId.equals(HOLDER_BODIES.get(strand.getUuid()))) continue;
			for (ClientRopePoint point : strand.getPoints()) {
				mapOnce(point.position(), moved, map);
				mapOnce(point.previousPosition(), moved, map);
				for (ClientRopePoint.Snapshot snapshot : point.snapshots()) mapOnce(snapshot.position(), moved, map);
			}
		}
	}

	/** Vectors may be shared between a point and its snapshots: each is moved exactly once. */
	private static void mapOnce(Vector3d v, Set<Vector3d> moved, Consumer<Vector3d> map) {
		if (moved.add(v)) map.accept(v);
	}
}
