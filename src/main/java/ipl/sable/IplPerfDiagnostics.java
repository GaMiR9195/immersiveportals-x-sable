package ipl.sable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Opt-in timing of IPSable's hot paths, enabled by {@code -Dipl.perfDiagnostics=true}: the
 * spatial-query splice of {@code CompanionIntersectMixin} (client and server), client projection
 * tree builds, the client's section draws of bodies crossing portals ({@code IplPieceRender},
 * CPU side), the transit sample at every solver boundary, Sable's server sub-level container
 * tick and the transit controller at its tail. One summary line every 5 seconds; counters reset
 * each time. When a path shows milliseconds per tick the bottleneck is there; when none does,
 * it is outside these paths. Disabled, every probe is a branch on a static final.
 */
public final class IplPerfDiagnostics {

    public static final boolean ENABLED = Boolean.getBoolean("ipl.perfDiagnostics");

    private static final long REPORT_INTERVAL_NANOS = 5_000_000_000L;
    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-perf");

    /** Calls, total and worst duration of one hot path; safe from any thread. */
    public static final class Counter {
        private final String name;
        private final LongAdder calls = new LongAdder();
        private final LongAdder nanos = new LongAdder();
        private final AtomicLong max = new AtomicLong();

        private Counter(String name) {
            this.name = name;
        }

        private void add(long dt) {
            calls.increment();
            nanos.add(dt);
            max.accumulateAndGet(dt, Math::max);
        }

        /** {@code name calls=N (n/tick) total=X ms (x ms/tick) max=Y µs}, then reset. */
        private String drain(double seconds) {
            long c = calls.sumThenReset();
            long n = nanos.sumThenReset();
            long m = max.getAndSet(0L);
            double ticks = Math.max(seconds * 20.0, 1.0);
            return String.format(Locale.ROOT, "%s calls=%d (%.0f/tick) total=%.2fms (%.3fms/tick) max=%dus",
                name, c, c / ticks, n / 1.0e6, n / 1.0e6 / ticks, m / 1000L);
        }
    }

    public static final Counter CLIENT_SPLICE = new Counter("client-splice");
    public static final Counter CLIENT_TREE = new Counter("client-tree");
    public static final Counter CLIENT_PIECE_DRAW = new Counter("client-piece-draw");
    public static final Counter SERVER_SAMPLE = new Counter("server-sample");
    public static final Counter SERVER_SPLICE = new Counter("server-splice");
    public static final Counter SERVER_CONTAINER = new Counter("server-container-tick");
    public static final Counter SERVER_TRANSIT = new Counter("server-transit");

    private static final AtomicLong LAST_REPORT = new AtomicLong();

    private IplPerfDiagnostics() {}

    public static void record(Counter counter, long nanos) {
        if (!ENABLED) return;
        counter.add(nanos);
        long now = System.nanoTime();
        long last = LAST_REPORT.get();
        if (last == 0L) {
            LAST_REPORT.compareAndSet(0L, now);
            return;
        }
        if (now - last < REPORT_INTERVAL_NANOS || !LAST_REPORT.compareAndSet(last, now)) return;
        double seconds = (now - last) / 1.0e9;
        LOG.info("[IPL-PERF] last {}s: {} | {} | {} | {} | {} | {} | {}", String.format(Locale.ROOT, "%.1f", seconds),
            CLIENT_SPLICE.drain(seconds), CLIENT_TREE.drain(seconds), CLIENT_PIECE_DRAW.drain(seconds),
            SERVER_SPLICE.drain(seconds), SERVER_SAMPLE.drain(seconds), SERVER_CONTAINER.drain(seconds),
            SERVER_TRANSIT.drain(seconds));
    }
}
