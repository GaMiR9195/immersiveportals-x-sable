package ipl.sable.atlas;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.IplSceneOwnership;
import ipl.sable.mixin.IplRapierPipelineAccess;
import ipl.sable.natives.IplRapierNatives;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Which Rapier chart a kinematic contraption's collider lives in.
 *
 * <p>Sable attaches a kinematic contraption (a Create bearing/piston/gantry structure riding
 * a ship) to its mount body as an extra voxel collider, created through the pipeline the
 * contraption was enrolled in. The collider's chart decides what it touches. A hosted
 * mount's body is simulated in the {@code ipl_sable:sublevels} chart, which contains no
 * terrain, so a collider enrolled there never touches the ground (or any ship's parent-chart
 * image). The world the contraption physically moves through is the mount's PARENT chart —
 * exactly where the mount's own image collider lives.
 *
 * <p>Atlas keeps one native world for every chart (shared rigid-body registry, global runtime
 * ids), so the parent pipeline can attach the collider to the hosted mount body directly;
 * the collider then rides the real body and pairs with parent terrain and images, like the
 * stock single-level case. Only when the shared world is unavailable (no IPL natives, or
 * the parent chart is not live) does the contraption stay in the mount's owning pipeline.
 *
 * <p>This class records the pipeline currently holding each contraption so removals
 * reach it from whichever level the entity is removed in, and re-enrolls contraptions
 * when their mount's parent chart changes.
 */
public final class IplContraptionCharts {
    private static final Logger LOG = LoggerFactory.getLogger("ipl-contraption-charts");

    /** Contraption -> level whose pipeline currently holds its native collider. */
    private static final Map<KinematicContraption, ServerLevel> HOLDER = new IdentityHashMap<>();

    private IplContraptionCharts() {}

    /**
     * The level whose pipeline must hold a contraption riding {@code mount}, or null when no
     * pipeline can safely create it (the mount has no native body anywhere — e.g. a
     * kinematic mirror, or a body not yet enrolled).
     */
    @Nullable
    public static ServerLevel chartFor(ServerSubLevel mount) {
        if (mount.isRemoved() || !(mount.getLevel() instanceof ServerLevel owner)) return null;
        if (!ownsBody(owner, mount)) return null;
        if (!IplDimAgnostic.isHosted(mount)) return owner;
        ServerLevel parent = IplDimAgnostic.getServerParentLevel(mount);
        if (parent == null || parent == owner || !IplRapierNatives.isAvailable()) return owner;
        if (IplSceneOwnership.liveSceneHandle(parent) == 0
            || IplSceneOwnership.liveSceneHandle(owner) == 0) {
            return owner;
        }
        return IplSceneOwnership.pipelineOf(parent) != null ? parent : owner;
    }

    private static boolean ownsBody(ServerLevel level, ServerSubLevel body) {
        RapierPhysicsPipeline pipeline = IplSceneOwnership.pipelineOf(level);
        return pipeline != null
            && ((IplRapierPipelineAccess) pipeline).ipl$activeSubLevels().containsKey(Rapier3D.getID(body));
    }

    public static void recordAdded(KinematicContraption contraption, ServerLevel holder) {
        HOLDER.put(contraption, holder);
    }

    public static void recordRemoved(KinematicContraption contraption, ServerLevel holder) {
        HOLDER.remove(contraption, holder);
    }

    /** The level whose pipeline holds {@code contraption}, if it is held anywhere. */
    @Nullable
    public static ServerLevel holderOf(KinematicContraption contraption) {
        ServerLevel holder = HOLDER.get(contraption);
        if (holder == null) return null;
        RapierPhysicsPipeline pipeline = IplSceneOwnership.pipelineOf(holder);
        if (pipeline == null || IplSceneOwnership.liveSceneHandle(holder) == 0
            || !((IplRapierPipelineAccess) pipeline).ipl$activeContraptions().containsKey(contraption)) {
            HOLDER.remove(contraption);
            return null;
        }
        return holder;
    }

    /**
     * Keep each contraption in its mount's current chart. Runs once per hosting tick; a
     * parent change (portal transit) re-enrolls the collider in the new parent's pipeline.
     */
    public static void reconcile() {
        if (HOLDER.isEmpty()) return;
        for (Map.Entry<KinematicContraption, ServerLevel> entry : new ArrayList<>(HOLDER.entrySet())) {
            KinematicContraption contraption = entry.getKey();
            ServerLevel holder = holderOf(contraption);
            if (holder == null) continue;
            RapierPhysicsPipeline holderPipeline = IplSceneOwnership.pipelineOf(holder);
            if (!contraption.sable$isValid()) {
                holderPipeline.remove(contraption);
                continue;
            }
            SubLevel mount = Sable.HELPER.getContaining(holder, contraption.sable$getPosition());
            if (!(mount instanceof ServerSubLevel serverMount)) continue;
            ServerLevel desired = chartFor(serverMount);
            if (desired == null || desired == holder) continue;
            RapierPhysicsPipeline target = IplSceneOwnership.pipelineOf(desired);
            if (target == null) continue;
            try {
                holderPipeline.remove(contraption);
                target.add(contraption);
            } catch (RuntimeException e) {
                LOG.warn("[IPL-CONTRAPTION] failed to move contraption {} from {} to {}",
                    contraption, holder.dimension().location(), desired.dimension().location(), e);
            }
        }
    }

    public static void clearAll() {
        HOLDER.clear();
    }
}
