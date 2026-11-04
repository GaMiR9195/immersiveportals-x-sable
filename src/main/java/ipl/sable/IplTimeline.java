package ipl.sable;

import net.minecraft.server.MinecraftServer;

/**
 * One snapshot timeline for every dimension of the server.
 *
 * <p>Sable stamps pose snapshots, clock packets and start-tracking with a per-level
 * {@code interpolationTick}; every level counts on its own, so the same server tick carries a
 * different stamp in each dimension. With portals one client shows several dimensions at once
 * and a ship moves between them, so {@code TrackingSystemMixin} replaces the per-level count
 * with this server-wide one: every level stamps a server tick with the same number, and the
 * client runs a single interpolation clock for all of them ({@code IplSharedClock}).
 *
 * <p>The stamp advances once per server tick in which any tracking system runs, exactly like
 * Sable's own counter (which advances once per tracking tick): a frozen server does not open
 * gaps in the timeline. Server thread only.
 */
public final class IplTimeline {

    private static int stamp = -1;
    private static int stampedServerTick = Integer.MIN_VALUE;

    private IplTimeline() {}

    /** The stamp of the current server tick. */
    public static int stamp(MinecraftServer server) {
        int tick = server.getTickCount();
        if (tick != stampedServerTick) {
            stampedServerTick = tick;
            stamp++;
        }
        return stamp;
    }

    /** Tick counts restart with every server (singleplayer worlds share the JVM). */
    public static void reset() {
        stamp = -1;
        stampedServerTick = Integer.MIN_VALUE;
    }
}
