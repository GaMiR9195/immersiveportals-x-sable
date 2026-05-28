package ipl.sable.render;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-sub-level contact state for portal straddling.
 *
 * <p><b>Why this exists:</b> the orient-toward-center heuristic in
 * {@link SourceClipPortalFinder} is unstable when the sub-level's AABB
 * center is close to a portal plane. As the airship rotates (especially
 * around the Y axis), the AABB center wobbles -- and if it briefly
 * crosses the portal plane, the heuristic flips the chosen normal,
 * inverting which half is "kept". Result: cog clipping that flips
 * direction as the airship rotates ("only works in one orientation").
 *
 * <p>The fix: once we've decided which side is the source for a given
 * sub-level + portal pair, stick with that decision as long as the
 * sub-level is still in contact with that portal. The first-contact
 * snapshot is anchored to the airship's natural pre-rotation orientation,
 * which is the orientation the player intuitively expects to be the
 * source side.
 *
 * <p><b>Lifecycle:</b>
 * <ul>
 *   <li>{@link #recordContact}: called per frame from
 *       {@link SourceClipPortalFinder} when a sub-level is found to be
 *       straddling. Records the chosen normal direction on first contact;
 *       returns the cached direction on subsequent calls.</li>
 *   <li>{@link #clearContact}: called when a sub-level no longer
 *       straddles ANY portal (e.g., the airship fully crossed or fully
 *       retreated). Resets the cache entry.</li>
 *   <li>The next time the sub-level enters contact, a fresh snapshot is
 *       taken from the current orient-toward-center decision.</li>
 * </ul>
 *
 * <p>Indexed by sub-level UUID alone (not (sub, portal) pair) because for
 * the test scene we only ever have one straddling portal per sub-level.
 * Multi-portal-per-sub-level extensions would add a portal UUID dimension.
 *
 * <p>Designed as a building block for future transit-aware features the
 * user mentioned (e.g., direction-of-motion tracking, persistent
 * source-dim/dest-dim labeling) -- this class is the contact-detection
 * layer those features can build on.
 */
public final class SubLevelPortalContactTracker {

    /**
     * Per-sub-level snapshot of the chosen plane-normal direction. Persists
     * across frames as long as the sub-level keeps straddling a portal.
     */
    private static final ConcurrentMap<UUID, Vec3> CHOSEN_NORMAL_BY_SUBLEVEL =
        new ConcurrentHashMap<>();

    private SubLevelPortalContactTracker() {}

    /**
     * Record (or read back) the chosen normal direction for a sub-level
     * currently in contact with a portal.
     *
     * <p>Behavior:
     * <ul>
     *   <li>If no cached entry exists for {@code subLevelId}: stores
     *       {@code freshlyOrientedNormal} and returns it.</li>
     *   <li>If a cached entry exists: returns the cached normal (regardless
     *       of what {@code freshlyOrientedNormal} would have been). The
     *       caller should USE the returned value to build its plane, so
     *       transient AABB-center wobbles don't flip the orientation.</li>
     * </ul>
     *
     * @param subLevelId sub-level identifier
     * @param freshlyOrientedNormal normal direction computed THIS frame
     *                              by the orient-toward-center logic;
     *                              used only on first contact
     * @return the normal direction to actually use for this frame's
     *         clip equation
     */
    public static Vec3 recordContact(UUID subLevelId, Vec3 freshlyOrientedNormal) {
        Vec3 prior = CHOSEN_NORMAL_BY_SUBLEVEL.putIfAbsent(
            subLevelId, freshlyOrientedNormal);
        return prior != null ? prior : freshlyOrientedNormal;
    }

    /**
     * Clear the cached entry for a sub-level that's no longer in contact
     * with any portal (so the next contact gets a fresh first-snapshot).
     * Cheap no-op if there's no entry.
     */
    public static void clearContact(UUID subLevelId) {
        CHOSEN_NORMAL_BY_SUBLEVEL.remove(subLevelId);
    }

    /**
     * For diagnostics: peek the current cached normal without modifying.
     * Returns null if no contact recorded.
     */
    @Nullable
    public static Vec3 peekChosenNormal(UUID subLevelId) {
        return CHOSEN_NORMAL_BY_SUBLEVEL.get(subLevelId);
    }
}
