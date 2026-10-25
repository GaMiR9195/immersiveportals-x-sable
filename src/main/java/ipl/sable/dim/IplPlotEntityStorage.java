package ipl.sable.dim;

import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.util.SubLevelInclusiveLevelEntityGetter;
import ipl.sable.mixin.IplInclusiveEntityGetterAccessor;
import ipl.sable.mixin.IplLevelEntityGetterInvoker;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.LevelEntityGetter;
import org.jetbrains.annotations.Nullable;

/**
 * Storage addressing for Sable's sub-level-inclusive entity queries.
 *
 * <p>Stock Sable keeps a sub-level's plot chunks — and the entities standing in them — in
 * the SAME level as the world the ship flies through, so
 * {@link SubLevelInclusiveLevelEntityGetter} answers every part of a query (plot-local
 * bounds, world-frame bounds, the local bounds of each intersecting sub-level) from one
 * storage. With plots hosted in {@code ipl_sable:sublevels} those parts live in different
 * levels:
 * <ul>
 *   <li>a sub-level's plot entities live in {@link SubLevel#getLevel()} (its storage);</li>
 *   <li>the world a sub-level flies through is its parent level;</li>
 *   <li>a world-frame query issued against the hosting level has no world of its own —
 *       it belongs to the armed world-frame context, if any.</li>
 * </ul>
 * This class names the storage that answers each part. It never guesses a dimension: an
 * unarmed world-frame query on the hosting level stays in storage, exactly as before.
 */
public final class IplPlotEntityStorage {
    private IplPlotEntityStorage() {}

    /**
     * The level whose world space a query against {@code self} is expressed in, given the
     * sub-level containing the query center (if any).
     */
    public static Level worldLevel(Level self, @Nullable SubLevel containing) {
        if (containing != null) {
            Level parent = IplDimAgnostic.getParentLevel(containing);
            return parent == null || IplDimAgnostic.isHostingLevel(parent) ? self : parent;
        }
        if (!IplDimAgnostic.isHostingLevel(self)) return self;
        Level context = IplEnvironmentFrame.contextualLevel(self);
        return context == null ? self : context;
    }

    /**
     * A second storage that must answer the query's own bounds besides {@code self}'s:
     * the containing sub-level's real storage when the query addresses its plot through a
     * parent level, or the armed world for a world-frame query on the hosting level.
     */
    @Nullable
    public static Level additionalPrimaryStorage(Level self, @Nullable SubLevel containing) {
        Level other = containing != null ? containing.getLevel() : worldLevel(self, null);
        return other == null || other == self ? null : other;
    }

    /** The storage holding the entities of {@code subLevel}'s plot. */
    public static Level plotStorage(Level self, SubLevel subLevel) {
        Level storage = subLevel.getLevel();
        return storage == null ? self : storage;
    }

    /**
     * Raw (non-inclusive) entity getter of {@code level}. Reading through the raw getter keeps
     * every routed sub-query non-recursive.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static LevelEntityGetter rawGetter(Level level) {
        LevelEntityGetter<Entity> getter = ((IplLevelEntityGetterInvoker) level).ipl$entityGetter();
        if (getter instanceof SubLevelInclusiveLevelEntityGetter inclusive) {
            return ((IplInclusiveEntityGetterAccessor) inclusive).ipl$getDelegate();
        }
        return getter;
    }
}
