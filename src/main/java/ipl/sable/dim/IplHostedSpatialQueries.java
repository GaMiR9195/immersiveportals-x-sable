package ipl.sable.dim;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.level.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
/** Read-only query view. Container membership, ticks and serialization are not changed. */
public final class IplHostedSpatialQueries {
    private IplHostedSpatialQueries() {}
    public static Iterable<SubLevel> include(Level source, BoundingBox3dc bounds, Iterable<SubLevel> original) {
        Level view = IplEnvironmentFrame.contextualLevel(source);
        if (view == null) view = source;
        boolean storage = IplDimAgnostic.isHostingLevel(source);
        if (storage && view == source) return original; // no owner => no guessed dimension
        var out = new ArrayList<SubLevel>();
        Set<SubLevel> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (SubLevel sub : original) {
            if (sub.isRemoved()) continue;
            if (storage && IplDimAgnostic.getParentLevel(sub) != view) continue;
            if (seen.add(sub)) out.add(sub);
        }
        var hosting = IplDimAgnostic.getHostingContainerFor(source);
        if (hosting == null) return out;
        for (SubLevel sub : IplOwnedSubLevels.owned(hosting)) {
            if (sub.isRemoved() || seen.contains(sub)) continue;
            boolean hit = IplDimAgnostic.getParentLevel(sub) == view && sub.boundingBox().intersects(bounds);
            if (!hit) {
                boolean[] mapped = {false};
                ipl.sable.transit.IplStraddlePoseMap.forEachStraddleInto(sub,view,(portal,mapping) -> {
                    if (!mapped[0] && mapping.mapAabb(sub.boundingBox()).intersects(bounds)) mapped[0] = true;
                });
                hit = mapped[0];
            }
            if (hit && seen.add(sub)) out.add(sub);
        }
        return out;
    }
}
