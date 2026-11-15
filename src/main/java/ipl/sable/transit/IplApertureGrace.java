package ipl.sable.transit;

import org.slf4j.LoggerFactory;

/**
 * How much wider than its frame every portal opening is taken to be, in blocks, by everything
 * that decides what passes it: the piece labels and sessions of bodies (server), the joint and
 * rope routing, and the routing of block-entity models through openings (client).
 *
 * <p>Rapier resolves contacts with a small penetration, and a body pressed against the frame of a
 * portal, or sliding along its rim, rests a little inside it. Taken at its exact size, the opening
 * would then see material a hair beside the rim as "not over the opening" one step and "over it"
 * the next. A slightly larger opening makes every such case pass cleanly instead of catching on
 * the frame. Nothing is drawn or teleported differently by Immersive Portals itself: only the
 * finite area of our own tests grows. The value is the same lateral growth on all four edges and
 * applies in the portal's own level (scaled with the opening where a test runs in a body's frame).
 *
 * <p>{@code -Dipl.sable.apertureGrace=<blocks>} (default {@value #DEFAULT}, clamped to
 * {@code [0, }{@value #MAX}{@code ]}). Client and server must use the same value.
 */
public final class IplApertureGrace {

	public static final double DEFAULT = 0.125;
	public static final double MAX = 0.5;

	/** Lateral growth of every opening, blocks. */
	public static final double LATERAL = read();

	private IplApertureGrace() {}

	private static double read() {
		String raw = System.getProperty("ipl.sable.apertureGrace");
		if (raw == null || raw.isBlank()) return DEFAULT;
		try {
			double v = Double.parseDouble(raw.trim());
			if (!Double.isFinite(v)) return DEFAULT;
			return Math.max(0.0, Math.min(MAX, v));
		} catch (NumberFormatException e) {
			LoggerFactory.getLogger("ipl-sable").warn("ipl.sable.apertureGrace={} is not a number; using {}", raw, DEFAULT);
			return DEFAULT;
		}
	}
}
