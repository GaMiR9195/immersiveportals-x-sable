package ipl.sable.atlas;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager;
import ipl.sable.dim.IplSceneOwnership;
import ipl.sable.mixin.IplRapierPipelineAccess;
import ipl.sable.natives.IplRapierNatives;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Skip all physics processing for hosted ships whose parent-pointer chunks are unloaded.
 *
 * <p>Stock Sable never simulates a sub-level over unloaded terrain — the holding system
 * serializes it out of the world first. Dim-agnostic hosting keeps ships always-live in
 * always-loaded plot chunks, which silently dropped that invariant: a nether ship with no
 * players in the nether simulates in mid-air against a chart whose terrain was never
 * baked, and falls into the void.
 *
 * <p>Restoration, vanilla-flavored: while the parent chunk under a ship's origin is not
 * loaded (the same {@code isChunkLoadedEnough} block-ticking test the terrain enrollment
 * uses), the ship goes DORMANT — its native body switches to Fixed (no integration, no
 * gravity, immovable; still a valid rope/joint anchor) and the enrollment pass skips it
 * entirely. No chunks are force-loaded. A player wandering back re-loads the area, the
 * gate flips, the body returns to Dynamic and wakes at rest exactly where it froze.
 *
 * <p>The dormant re-apply runs every tick (idempotent native), which also re-freezes a
 * body recreated mid-dormancy (rehome twin swap). Kill switch:
 * {@code -Dipl.sable.parentLoadGate=false}.
 */
public final class IplHostedTerrainGate {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-terrain-gate");

    private static final boolean ENABLED =
        !"false".equalsIgnoreCase(System.getProperty("ipl.sable.parentLoadGate", "true"));

    private static final Set<UUID> DORMANT = new HashSet<>();

    private IplHostedTerrainGate() {}

    /**
     * Called from the parent-level enrollment pass for each hosted ship of that level.
     * Returns true when the ship is dormant and the caller should skip all further
     * processing (terrain enrollment, velocity prediction) for it this tick.
     */
    public static boolean tick(ServerLevel parent, PhysicsPipeline pipeline, ServerSubLevel sub) {
        if (!ENABLED) return false;

        var pos = sub.logicalPose().position();
        int cx = SectionPos.blockToSectionCoord(Mth.floor(pos.x()));
        int cz = SectionPos.blockToSectionCoord(Mth.floor(pos.z()));
        UUID id = sub.getUniqueId();

        if (PhysicsChunkTicketManager.isChunkLoadedEnough(parent, cx, cz)) {
            if (DORMANT.remove(id)) {
                setDormant(sub, false);
                pipeline.wakeUp(sub);
                LOG.info("[IPL-TERRAIN-GATE] woke ship {} in {} — parent chunk [{}, {}] loaded",
                    id, parent.dimension().location(), cx, cz);
            }
            return false;
        }

        if (DORMANT.add(id)) {
            LOG.info("[IPL-TERRAIN-GATE] ship {} dormant in {} — parent chunk [{}, {}] not "
                + "loaded", id, parent.dimension().location(), cx, cz);
        }
        setDormant(sub, true); // every tick: idempotent, and re-freezes a rehome twin
        return true;
    }

    private static void setDormant(ServerSubLevel sub, boolean dormant) {
        if (!IplRapierNatives.isAvailable()) return;
        RapierPhysicsPipeline owning =
            IplSceneOwnership.pipelineOf((ServerLevel) sub.getLevel());
        if (owning == null) return;
        // Liveness via the raw scene FIELD: the sceneHandle invoker NPEs on a torn-down
        // pipeline (server-stop ordering).
        if (((IplRapierPipelineAccess) owning).ipl$scene() == null) return;
        long scene = ((IplRapierPipelineAccess) owning).ipl$sceneHandle();
        IplRapierNatives.setBodyDormant(scene, Rapier3D.getID(sub), dormant);
    }

    public static void clearAll() {
        DORMANT.clear();
    }
}
