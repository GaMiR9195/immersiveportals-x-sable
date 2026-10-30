package ipl.sable.transit;

import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.physics.constraint.FreeConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipl.sable.atlas.IplScenes;
import ipl.sable.natives.IplRapierNatives;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * "Is this sub-level attached to something?" — while it is, a finished crossing does
 * not flip: the session stays open and the far side keeps living on the projection.
 *
 * <ul>
 *   <li>non-Free constraints to the static world (staff lock, stickers, anchors)
 *       block; Free constraints to the world (physics-staff drag, handles) are motor
 *       drivers, not attachments, and are ignored;</li>
 *   <li>joints to sub-levels outside the rigid group block (they would be torn
 *       across the seam);</li>
 *   <li>any rope attached to a group member blocks;</li>
 *   <li>a jointed group never moves to another dimension (joints cannot follow a
 *       level change); it keeps flying on its projection instead.</li>
 * </ul>
 */
public final class IplAttachmentGate {
    private static final Map<ServerSubLevel, List<PhysicsConstraintHandle>> WORLD_LINKS = new WeakHashMap<>();

    private IplAttachmentGate() {}

    /** Called for every constraint Sable creates (server thread). */
    public static synchronized void onConstraintAdded(@Nullable PhysicsPipelineBody a, @Nullable PhysicsPipelineBody b,
                                                      PhysicsConstraintConfiguration<?> config,
                                                      @Nullable PhysicsConstraintHandle handle) {
        if (handle == null || config instanceof FreeConstraintConfiguration) return;
        if ((a == null) == (b == null)) return; // body-body joints are read from the natives
        if (!((a == null ? b : a) instanceof ServerSubLevel sub)) return;
        WORLD_LINKS.computeIfAbsent(sub, ignored -> new ArrayList<>(1)).add(handle);
    }

    public static synchronized boolean isWorldAnchored(ServerSubLevel sub) {
        List<PhysicsConstraintHandle> links = WORLD_LINKS.get(sub);
        if (links == null) return false;
        links.removeIf(handle -> {
            try {
                return !handle.isValid();
            } catch (Throwable t) {
                return true;
            }
        });
        if (links.isEmpty()) {
            WORLD_LINKS.remove(sub);
            return false;
        }
        return true;
    }

    /** Reason the group must not flip right now, or null when it may. */
    @Nullable
    public static String flipBlocker(ServerSubLevel ship, List<ServerSubLevel> mates, boolean crossDimension) {
        if (isWorldAnchored(ship)) return "anchored";
        for (ServerSubLevel mate : mates) if (isWorldAnchored(mate)) return "anchored";
        if (crossDimension && !mates.isEmpty()) return "jointed-group";
        if (!IplScenes.isEnabled() || !(ship.getLevel() instanceof ServerLevel level)) return null;
        long scene = IplScenes.sceneHandle(level);
        if (scene == 0) return null;
        try {
            int[] linked = IplRapierNatives.connectedSableBodyIds(scene, Rapier3D.getID(ship), false);
            if (linked.length > 1 + mates.size()) return "linked";
            if (IplRapierNatives.ropesAttachedToSableBody(scene, Rapier3D.getID(ship)).length > 0) return "rope";
            for (ServerSubLevel mate : mates) {
                if (IplRapierNatives.ropesAttachedToSableBody(scene, Rapier3D.getID(mate)).length > 0) return "rope";
            }
        } catch (UnsatisfiedLinkError ignored) {
            // Older natives without the queries: only the Java-side world links apply.
        }
        return null;
    }

    public static synchronized void clearAll() {
        WORLD_LINKS.clear();
    }
}
