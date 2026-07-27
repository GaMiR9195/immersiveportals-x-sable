package ipl.sable.atlas;

import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.IplSceneOwnership;
import ipl.sable.mixin.IplRapierPipelineAccess;
import ipl.sable.natives.IplRapierNatives;
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

        long scene = 0;
        if (!hosted.isEmpty()) {
            RapierPhysicsPipeline pipeline =
                IplSceneOwnership.pipelineOf((ServerLevel) hosted.get(0).getLevel());
            if (pipeline == null || ((IplRapierPipelineAccess) pipeline).ipl$scene() == null) {
                return;
            }
            scene = ((IplRapierPipelineAccess) pipeline).ipl$sceneHandle();
        }

        Set<Long> desired = new HashSet<>();
        for (int i = 0; i < hosted.size(); i++) {
            ServerSubLevel a = hosted.get(i);
            ServerLevel parentA = IplDimAgnostic.getServerParentLevel(a);
            int idA = Rapier3D.getID(a);
            for (int j = i + 1; j < hosted.size(); j++) {
                ServerSubLevel b = hosted.get(j);
                ServerLevel parentB = IplDimAgnostic.getServerParentLevel(b);
                boolean cross = parentA == null || parentB == null || parentA != parentB;
                // Image colliders SHARE their real body's id, so this exclusion would
                // also drop the legitimate image-vs-image contact when one ship
                // straddles a portal INTO the other's dimension. Carve that out: while
                // a session maps A into B's parent (or vice versa) the pair stays
                // collidable — their raw hosting-chart poses are portal-source vs dest
                // coordinates, far apart numerically, so the accidental-overlap case
                // this class exists for cannot occur in that window anyway.
                if (cross && parentA != null && parentB != null
                    && (ipl.sable.transit.IplAtlasStraddleSession.getMappingInto(a, parentB) != null
                        || ipl.sable.transit.IplAtlasStraddleSession.getMappingInto(b, parentA) != null)) {
                    cross = false;
                }
                if (cross) {
                    desired.add(pack(idA, Rapier3D.getID(b)));
                }
            }
        }

        for (long key : desired) {
            if (EXCLUDED.add(key) && scene != 0) {
                IplRapierNatives.setBodyPairExclusion(scene, idA(key), idB(key), true);
                LOG.debug("[IPL-CROSS-PARENT] excluded pair {}<->{}", idA(key), idB(key));
            }
        }
        if (EXCLUDED.size() > desired.size()) {
            final long sceneF = scene;
            EXCLUDED.removeIf(key -> {
                if (desired.contains(key)) return false;
                if (sceneF != 0) {
                    IplRapierNatives.setBodyPairExclusion(sceneF, idA(key), idB(key), false);
                }
                return true;
            });
        }
    }

    public static void clearAll() {
        EXCLUDED.clear();
    }

    private static long pack(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xFFFF_FFFFL);
    }

    private static int idA(long key) {
        return (int) (key >>> 32);
    }

    private static int idB(long key) {
        return (int) key;
    }
}
