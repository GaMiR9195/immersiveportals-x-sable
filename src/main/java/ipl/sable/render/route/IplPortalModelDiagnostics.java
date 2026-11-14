package ipl.sable.render.route;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * {@code -Dipl.portalModelDiagnostics=true}: once a second, what portal routing cost and did
 * (models recorded, cut, drawn directly, time in recording, solving and writing, Flywheel
 * hand-overs), plus every change of a model's reach and every Flywheel hand-over as it happens
 * (rate-limited). Free when off. Render thread only.
 */
public final class IplPortalModelDiagnostics {

	public static final boolean ENABLED = Boolean.getBoolean("ipl.portalModelDiagnostics");
	private static final Logger LOG = LoggerFactory.getLogger("ipl-portal-models");
	private static final int EVENTS_PER_SECOND = 40;

	private static final Map<BlockEntity, String> LAST_REACH = new WeakHashMap<>();
	private static final Map<BlockEntity, String> LAST_ROUTE = new WeakHashMap<>();
	private static long windowStart = System.nanoTime();
	private static int frames, events;
	private static int recorded, cut, direct, written, handedOut, handedBack;
	private static long recordNanos, solveNanos, writeNanos, primitives, parts;

	private IplPortalModelDiagnostics() {}

	static long start() {
		return ENABLED ? System.nanoTime() : 0L;
	}

	static void recorded(long start) {
		if (!ENABLED) return;
		recorded++;
		recordNanos += System.nanoTime() - start;
	}

	static void solved(long start, int primitiveCount, int partCount) {
		if (!ENABLED) return;
		cut++;
		solveNanos += System.nanoTime() - start;
		primitives += primitiveCount;
		parts += partCount;
	}

	static void direct() {
		if (ENABLED) direct++;
	}

	static void written(long start) {
		if (!ENABLED) return;
		written++;
		writeNanos += System.nanoTime() - start;
	}

	static void reach(BlockEntity be, String reach, int frameDepth) {
		if (!ENABLED) return;
		String old = LAST_REACH.put(be, reach);
		if (reach.equals(old)) return;
		event("reach {} -> {} {} {} frameDepth={}", old, reach, type(be), be.getBlockPos(), frameDepth);
	}

	/** A routed model's split changed (logged on change only). */
	static void route(BlockEntity be, String summary) {
		String old = LAST_ROUTE.put(be, summary);
		if (summary.equals(old)) return;
		event("route {} {} {} (was {})", type(be), be.getBlockPos(), summary, old);
	}

	public static void handOver(BlockEntity be, boolean toRenderer) {
		if (!ENABLED) return;
		if (toRenderer) handedOut++;
		else handedBack++;
		event("flywheel {} {} {}", toRenderer ? "-> renderer" : "-> flywheel", type(be), be.getBlockPos());
	}

	/** Start of a render frame (outermost level pass). */
	static void frame() {
		if (!ENABLED) return;
		frames++;
		long now = System.nanoTime();
		if (now - windowStart < 1_000_000_000L) return;
		LOG.info("portal models/s: frames={} recorded={} ({} ms) cut={} ({} ms, {} primitives -> {} parts) direct={} writes={} ({} ms) flywheel out={} back={}",
			frames, recorded, ms(recordNanos), cut, ms(solveNanos), primitives, parts, direct, written, ms(writeNanos),
			handedOut, handedBack);
		windowStart = now;
		frames = events = recorded = cut = direct = written = handedOut = handedBack = 0;
		recordNanos = solveNanos = writeNanos = primitives = parts = 0;
	}

	private static void event(String format, Object... args) {
		if (events++ < EVENTS_PER_SECOND) LOG.info(format, args);
	}

	private static String type(BlockEntity be) {
		return String.valueOf(BlockEntityType.getKey(be.getType()));
	}

	private static String ms(long nanos) {
		return String.format("%.2f", nanos / 1.0e6);
	}
}
