package ipl.sable.atlas;

import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.IplSceneOwnership;
import ipl.sable.natives.IplRapierNatives;
import ipl.sable.transit.IplAtlasStraddleSession;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ships in different parent dimensions must never collide.
 *
 * <p>Every hosted ship's real body lives in the hosting chart at PARENT-FRAME
 * coordinates — an overworld ship at (100, 70, 100) and a nether ship at (100, 70, 100)
 * overlap numerically while being worlds apart semantically. Chart tagging separates them
 * from each other's TERRAIN (per-chart static colliders), but real-vs-real dynamic pairs
 * share the hosting chart and the dispatcher happily manifolds them.
 *
 * <p>Fix: maintain a native contact exclusion for every cross-parent pair of hosted
 * bodies (the same {@code ipl_excluded_pairs} machinery portal rims use — the
 * dynamic-vs-dynamic dispatcher path drops manifolds for excluded pairs, and the
 * {@code is_static} flags are shape-level, so dormant Fixed ships are covered too).
 * Same-parent pairs stay collidable; pairs are re-derived every hosting tick so parent
 * flips (transit) update exclusions within a tick. A ship whose parent is still
 * unresolved (boot restore window) is conservatively excluded from everything.
 *
 * <p>Deeper follow-up on record: a parent-frame id on the native collider info, checked
 * in the dispatcher beside the chart guard, would replace this pair bookkeeping AND the
 * straddle carve-out below with a per-body scalar. This class is the Java-side interim.
 */
public final class IplCrossParentExclusions {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-cross-parent");

    /** Mirror of the native exclusion entries WE own (packed body-id pairs). Never
     *  touches pairs registered by others (portal rims). */
    private static final Set<Long> EXCLUDED = new HashSet<>();

    private IplCrossParentExclusions() {}

    /** Re-derive cross-parent exclusions. Called once per hosting-container tick. */
    public static void reconcile(Iterable<? extends ServerSubLevel> subLevels) {
        if (!IplRapierNatives.isAvailable()) return;

        List<ServerSubLevel> hosted = new ArrayList<>();
        for (ServerSubLevel sub : subLevels) {
            if (sub.isRemoved() || !IplDimAgnostic.isHosted(sub)) continue;
            if (Rapier3D.getID(sub) < 0) continue;
            hosted.add(sub);
        }
        if (hosted.isEmpty() && EXCLUDED.isEmpty()) return;

        long scene = hosted.isEmpty() ? 0
            : IplSceneOwnership.liveSceneHandle((ServerLevel) hosted.get(0).getLevel());
        if (!hosted.isEmpty() && scene == 0) return;

        int n = hosted.size();
        ServerLevel[] parents = new ServerLevel[n];
        int[] ids = new int[n];
        for (int i = 0; i < n; i++) {
            parents[i] = IplDimAgnostic.getServerParentLevel(hosted.get(i));
            ids[i] = Rapier3D.getID(hosted.get(i));
        }

        Set<Long> desired = new HashSet<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                boolean cross = parents[i] == null || parents[j] == null
                    || parents[i] != parents[j];
                // Image colliders SHARE their real body's id, so this exclusion would
                // also drop the legitimate image-vs-image contact when one ship
                // straddles a portal INTO the other's dimension. Carve that out: while
                // a session maps A into B's parent (or vice versa) the pair stays
                // collidable — their raw hosting-chart poses are portal-source vs dest
                // coordinates, far apart numerically, so the accidental-overlap case
                // this class exists for cannot occur in that window anyway.
                if (cross && parents[i] != null && parents[j] != null
                    && (IplAtlasStraddleSession.getMappingInto(hosted.get(i), parents[j]) != null
                        || IplAtlasStraddleSession.getMappingInto(hosted.get(j), parents[i]) != null)) {
                    cross = false;
                }
                if (cross) {
                    desired.add(pack(ids[i], ids[j]));
                }
            }
        }

        final long sceneF = scene;
        for (long key : desired) {
            if (EXCLUDED.add(key) && sceneF != 0) {
                IplRapierNatives.setBodyPairExclusion(sceneF, pairLo(key), pairHi(key), true);
                LOG.debug("[IPL-CROSS-PARENT] excluded pair {}<->{}", pairLo(key), pairHi(key));
            }
        }
        EXCLUDED.removeIf(key -> {
            if (desired.contains(key)) return false;
            if (sceneF != 0) {
                IplRapierNatives.setBodyPairExclusion(sceneF, pairLo(key), pairHi(key), false);
            }
            return true;
        });
    }

    public static void clearAll() {
        EXCLUDED.clear();
    }

    private static long pack(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xFFFF_FFFFL);
    }

    private static int pairLo(long key) {
        return (int) (key >>> 32);
    }

    private static int pairHi(long key) {
        return (int) key;
    }
}
