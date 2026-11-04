package ipl.sable.render;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Debug counters of the vanilla sub-level clip path ({@code ChunkedRenderDataMixin}): how
 * often a sub-level draw ran and how often it actually installed a clip plane. Enabled by
 * {@code -Dipl.portalDiagnostics=true}; one summary every 5 seconds, counters reset each time.
 * Render thread only.
 */
public final class IplClipDiagnostics {

    private static final boolean DIAGNOSTICS = Boolean.getBoolean("ipl.portalDiagnostics");
    private static final long REPORT_INTERVAL_NANOS = 5_000_000_000L;

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-clip");

    private static long calls;
    private static long installs;
    private static long lastReportNanos;

    private IplClipDiagnostics() {}

    public static void onVanillaCall(boolean installed) {
        if (!DIAGNOSTICS) return;
        calls++;
        if (installed) installs++;
        long now = System.nanoTime();
        if (lastReportNanos == 0L) {
            lastReportNanos = now;
            return;
        }
        if (now - lastReportNanos < REPORT_INTERVAL_NANOS) return;
        LOG.info("[IPL-CLIP] last 5s: vanilla calls={} installs={}", calls, installs);
        calls = 0;
        installs = 0;
        lastReportNanos = now;
    }
}
