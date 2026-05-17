package ipl.sable.transit;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Thread-local registry of UUIDs that are currently being allocated as kinematic
 * mirrors. Used by {@code SableMirrorPhysicsOptOutMixin} to identify mirror sub-levels
 * at the moment they're being added to a {@code SubLevelContainer} (the observer
 * fires synchronously inside {@code allocateSubLevel}, before we can call
 * {@code ipl$setKinematicMirror(true)} on the resulting instance).
 *
 * <p>Usage from mirror-spawn code:
 * <pre>
 *   UUID mirrorUuid = UUID.randomUUID();
 *   IplMirrorIncomingTracker.markIncoming(mirrorUuid);
 *   try {
 *       ServerSubLevel mirror = (ServerSubLevel) destContainer.allocateSubLevel(
 *           mirrorUuid, plotX, plotZ, mirrorPose
 *       );
 *       // The physics opt-out mixin saw the UUID in the tracker and cancelled
 *       // pipeline.add; it also set the kinematic flag on the new sub-level.
 *   } finally {
 *       IplMirrorIncomingTracker.unmarkIncoming(mirrorUuid);
 *   }
 * </pre>
 *
 * <p>Thread-local because sub-level allocation happens on the server thread, and we
 * want strictly scoped visibility — no cross-thread contamination.
 */
public final class IplMirrorIncomingTracker {

    private static final ThreadLocal<Set<UUID>> INCOMING =
        ThreadLocal.withInitial(HashSet::new);

    private IplMirrorIncomingTracker() {}

    public static void markIncoming(UUID uuid) {
        INCOMING.get().add(uuid);
    }

    public static boolean isIncoming(UUID uuid) {
        return INCOMING.get().contains(uuid);
    }

    public static void unmarkIncoming(UUID uuid) {
        INCOMING.get().remove(uuid);
    }
}
