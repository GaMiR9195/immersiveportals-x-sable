package ipl.sable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-thread stall watchdog.
 *
 * <p>The integrated server can hang with no exception and no log -- e.g. an
 * infinite loop from a corrupted data structure, or a deadlock. When that
 * happens we have no idea <i>where</i> it's stuck, because the server thread
 * simply stops emitting anything (the 29May mirror-churn hang looked exactly
 * like this: last server log line was a mirror spawn, then silence while the
 * render thread kept going).
 *
 * <p>This watchdog runs a daemon thread that watches a "last tick progressed"
 * timestamp, updated each server tick via {@link #onTick(Thread)}. If the
 * server thread goes {@link #STALL_THRESHOLD_NANOS} without progressing, it
 * captures and logs that thread's current stack trace -- pinpointing the stuck
 * frame. It re-dumps every {@link #REDUMP_INTERVAL_NANOS} while the stall
 * persists, so a <i>moving</i> stack (infinite loop) can be told apart from a
 * <i>static</i> one (deadlock / blocked I/O).
 *
 * <p>Cheap and silent in normal operation: it only ever logs when a tick takes
 * multiple seconds, which should never happen in healthy play. Purely
 * diagnostic -- it does not attempt to recover the server.
 */
public final class IplServerWatchdog {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-watchdog");

    /** How long the server thread may go without a completed tick before we dump. */
    private static final long STALL_THRESHOLD_NANOS = 8_000_000_000L; // 8s

    /** While still stalled, re-dump no more often than this. */
    private static final long REDUMP_INTERVAL_NANOS = 10_000_000_000L; // 10s

    private static volatile Thread serverThread;
    private static volatile long lastProgressNanos = System.nanoTime();
    private static volatile long tickCount = 0L;
    private static volatile long lastDumpNanos = 0L;
    private static volatile boolean watchdogStarted = false;

    private IplServerWatchdog() {}

    /**
     * Call once per completed server tick (from the heartbeat mixin's TAIL). Records
     * the server thread reference + a fresh progress timestamp, and lazily starts the
     * watchdog daemon on first call.
     */
    public static void onTick(Thread thread) {
        serverThread = thread;
        lastProgressNanos = System.nanoTime();
        tickCount++;
        if (!watchdogStarted) {
            startWatchdog();
        }
    }

    private static synchronized void startWatchdog() {
        if (watchdogStarted) return;
        watchdogStarted = true;
        Thread t = new Thread(IplServerWatchdog::watchdogLoop, "ipl-server-watchdog");
        t.setDaemon(true);
        t.start();
        LOG.info("[IPL-WATCHDOG] started (stall threshold {}s)", STALL_THRESHOLD_NANOS / 1_000_000_000L);
    }

    private static void watchdogLoop() {
        while (true) {
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException e) {
                return;
            }

            Thread st = serverThread;
            if (st == null) continue;

            long now = System.nanoTime();
            long stalledNanos = now - lastProgressNanos;
            if (stalledNanos < STALL_THRESHOLD_NANOS) continue;
            if (now - lastDumpNanos < REDUMP_INTERVAL_NANOS) continue;

            lastDumpNanos = now;

            StackTraceElement[] stack = st.getStackTrace();
            StringBuilder sb = new StringBuilder(2048);
            sb.append("[IPL-WATCHDOG] SERVER THREAD STALLED ~")
                .append(stalledNanos / 1_000_000_000L)
                .append("s (lastCompletedTick=").append(tickCount)
                .append(", thread=").append(st.getName())
                .append(", state=").append(st.getState())
                .append("). Stack:\n");
            if (stack.length == 0) {
                sb.append("    <no stack frames available>\n");
            } else {
                for (StackTraceElement e : stack) {
                    sb.append("    at ").append(e).append('\n');
                }
            }
            LOG.error(sb.toString());
        }
    }
}
