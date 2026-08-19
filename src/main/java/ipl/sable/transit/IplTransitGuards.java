package ipl.sable.transit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tuning and diagnostics for the crossing guards.
 *
 * <p>WHY THIS EXISTS. Every refusal in {@code SableTransitController}'s back-face guard is
 * a bare {@code continue}. A refused crossing is therefore indistinguishable, in the log,
 * from a crossing that was never detected and from a body that has no portal near it — and
 * those three states need completely different fixes. A 20-second capture of a body in free
 * fall past a portal loop contains no {@code [IPL-IMAGE]} and no {@code [IPL-TRANSIT]} line
 * at all, which is exactly what a silent guard looks like from the outside.
 *
 * <p>So the guards get a name, a rate-limited log line, and a switch each. Nothing here
 * changes behaviour on its own: the constants are read by the detector and the controller,
 * and every default reproduces the current behaviour bit for bit.
 *
 * <p>See {@code docs/sweep-session-findings.md} for the two defects these switches gate and
 * for the exact hunks that wire them in.
 */
public final class IplTransitGuards {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-transit-guards");

    /** One line per body per second: a refused crossing repeats every tick by nature. */
    private static final long DIAG_INTERVAL_MS = 1_000L;

    private static final Map<UUID, Long> LAST_DIAG = new HashMap<>();
    private static final Map<UUID, String> LAST_REASON = new HashMap<>();

    /**
     * DEFECT 1 (see the doc): {@code withinExitClearance} is pure overlap + plate identity
     * with NO direction test, while its sibling {@code entryWithinExitSlab} has one. The
     * plate is the DESTINATION rectangle, and in a portal loop that rectangle belongs to the
     * paired portal — the very doorway the body must use next. So a body that reaches the
     * paired portal within {@link #EXIT_LATCH_MAX_TICKS} ticks has its next, entirely
     * legitimate crossing refused, which is a pure SPEED threshold: travel per tick greater
     * than (portal spacing / latch ticks). That is the "at 150+ it just passes through"
     * report, and it is the same failure mode the {@code armExitClearance} comment describes
     * for the old entrance-side plate — moving the plate to the destination moved the bug
     * from portal A to portal B instead of removing it.
     *
     * <p>With this enabled the guard refuses only motion travelling BACK into the plate
     * (the property that actually defines a back-face re-entry), so a body flying forward
     * through a loop is never refused. Set {@code -Dipl.sable.directionalExitClearance=false}
     * to restore the position-only guard.
     */
    public static final boolean DIRECTIONAL_EXIT_CLEARANCE =
        !"false".equalsIgnoreCase(System.getProperty("ipl.sable.directionalExitClearance"));

    /**
     * DEFECT 2 (see the doc): {@code plateIsRectangleOf} compares the candidate portal's
     * ORIGIN against the plate, and the plate is the mapped DESTINATION rectangle. For the
     * entrance face those points differ by the whole portal isometry, so the test is false
     * for the entrance portal by construction. Every consumer that asks about the entrance
     * face therefore gets "no": {@code touchesPortalVirtually(body, entranceFace)} is always
     * false after a transit, which makes the exit-hysteresis branch in
     * {@code SableTransitController} (the branch whose whole job is to keep the seam alive
     * while the body is still leaving) dead code — the session is torn down on the tick
     * after it was built, or lingers until an unrelated reap notices it. Both halves of that
     * are visible as the Creative Physics Staff beam routing against a session that no
     * longer agrees with the body.
     *
     * <p>With this enabled the latch also matches the portal it was FILED under (the
     * entrance), in addition to the geometric plate match, so identity answers correctly for
     * both faces of the doorway. Set {@code -Dipl.sable.latchMatchesEntrance=false} to
     * restore geometry-only identity.
     */
    public static final boolean LATCH_MATCHES_ENTRANCE =
        !"false".equalsIgnoreCase(System.getProperty("ipl.sable.latchMatchesEntrance"));

    /**
     * Hard safety ceiling on the exit plate, in trail ticks. Default 3, i.e. unchanged.
     *
     * <p>This is deliberately a property and not a new default. The detector's own comment
     * records that raising it to 40 cut loop survival in half, and with DEFECT 1 present
     * that is exactly what must happen: a longer latch means a longer window in which the
     * paired portal is refused. The two knobs are meant to be bisected TOGETHER — with
     * directional clearance on, a longer plate costs nothing, because forward motion is no
     * longer refused at all.
     */
    public static final long EXIT_LATCH_MAX_TICKS =
        Long.getLong("ipl.sable.exitLatchTicks", 3L);

    private IplTransitGuards() {}

    /**
     * A crossing that was DETECTED and then refused by a guard. Rate-limited per body, and
     * additionally emitted immediately whenever the refusing guard changes, so a body that
     * switches from one refusal to another is never hidden by the rate limit.
     *
     * @param guard         name of the guard that refused, e.g. {@code "entry-within-exit-slab"}
     * @param travelPerTick length of the retained segment; the threshold in DEFECT 1 is
     *                      stated in exactly these units
     */
    public static void refusedCrossing(
        UUID shipUuid, UUID portalUuid, String guard, double travelPerTick
    ) {
        if (shipUuid == null) return;
        long now = System.currentTimeMillis();
        Long last = LAST_DIAG.get(shipUuid);
        boolean changed = !guard.equals(LAST_REASON.get(shipUuid));
        if (!changed && last != null && now - last < DIAG_INTERVAL_MS) return;
        LAST_DIAG.put(shipUuid, now);
        LAST_REASON.put(shipUuid, guard);
        LOG.info("[IPL-TRANSIT-GUARD] refused ship={} portal={} guard={} travel/tick={} "
                + "latchTicks={} directional={} entranceIdentity={}",
            shipUuid, portalUuid, guard, String.format("%.2f", travelPerTick),
            EXIT_LATCH_MAX_TICKS, DIRECTIONAL_EXIT_CLEARANCE, LATCH_MATCHES_ENTRANCE);
    }

    /** Forget per-body diagnostic state (server stop / trail clear). */
    public static void clear() {
        LAST_DIAG.clear();
        LAST_REASON.clear();
    }
}
