package ipl.sable.dim;

import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.duck.IplSubLevelDuck;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Master gate + helpers for the dim-agnostic sub-level model (REFACTOR_SPEC.md, Option B).
 *
 * <p>When enabled, every {@code ServerSubLevel} is rehomed shortly after creation into the
 * {@code ipl_sable:sublevels} hosting dimension ({@link SableSubLevelDimension#SUBLEVELS}) by
 * {@link ipl.sable.transit.SableRehomeOps}. From then on:
 * <ul>
 *   <li>{@code subLevel.getLevel()} (Sable's field) == the hosting level — chunk storage,
 *       physics pipeline, container membership, persistence all live there.</li>
 *   <li>{@link IplSubLevelDuck#ipl$getParentLevel()} == the dimension the airship visually
 *       appears in. Tracking range checks, portal queries, terrain collision enrollment and
 *       rendering all use the parent.</li>
 *   <li>Cross-portal transit flips {@code parentLevel} (plus a pose remap) — plot chunks
 *       never move again.</li>
 * </ul>
 *
 * <p>Kill switch: {@code -Dipl.sable.dimAgnostic=false} reverts to the legacy
 * embedded-in-parent model (mirrors + copy transits) without rebuilding.
 */
public final class IplDimAgnostic {

    private static final boolean ENABLED =
        !"false".equalsIgnoreCase(System.getProperty("ipl.sable.dimAgnostic", "true"));

    private IplDimAgnostic() {}

    public static boolean isEnabled() {
        return ENABLED;
    }

    /** Is this level the dedicated hosting dimension? */
    public static boolean isHostingLevel(@Nullable Level level) {
        return level != null && level.dimension() == SableSubLevelDimension.SUBLEVELS;
    }

    /**
     * Is this sub-level rehomed into the hosting dimension? True iff its container/chunk level
     * is {@code ipl_sable:sublevels}. (The duck's hostingLevel field mirrors the constructor's
     * level, so checking the real level is the authoritative test on both sides.)
     */
    public static boolean isHosted(@Nullable SubLevel subLevel) {
        return subLevel != null && isHostingLevel(subLevel.getLevel());
    }

    /**
     * The parent level of a hosted sub-level — where it visually appears and physically
     * interacts. Falls back to {@code getLevel()} for non-hosted sub-levels (legacy model,
     * where parent == hosting == container level).
     */
    public static Level getParentLevel(SubLevel subLevel) {
        Level parent = ((IplSubLevelDuck) subLevel).ipl$getParentLevel();
        return parent != null ? parent : subLevel.getLevel();
    }

    /**
     * Server-side parent of a hosted sub-level, or null if the parent is unset/unresolvable
     * (e.g. freshly deserialized before {@code SableRehomeOps} restored it from user data).
     */
    @Nullable
    public static ServerLevel getServerParentLevel(SubLevel subLevel) {
        Level parent = getParentLevel(subLevel);
        if (parent instanceof ServerLevel serverLevel && !isHostingLevel(serverLevel)) {
            return serverLevel;
        }
        return null;
    }

    /**
     * The hosting dimension's sub-level container, resolved from any sided context level
     * (non-creating on the client). Null when the hosting dim/world isn't available yet.
     */
    @Nullable
    public static dev.ryanhcode.sable.api.sublevel.SubLevelContainer getHostingContainerFor(Level context) {
        if (context instanceof ServerLevel serverLevel) {
            ServerLevel hosting = SableSubLevelDimension.getSableSubLevelsOrNull(serverLevel.getServer());
            return hosting == null ? null
                : dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer((Level) hosting);
        }
        if (context.isClientSide) {
            // Client-only class; loaded lazily, only when this branch executes.
            return ipl.sable.client.IplClientHostedLookup.getHostingContainerOrNull();
        }
        return null;
    }
}
