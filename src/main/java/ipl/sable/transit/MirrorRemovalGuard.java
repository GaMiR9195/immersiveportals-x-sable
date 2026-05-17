package ipl.sable.transit;

/**
 * Static thread-local-ish flag indicating that the currently-executing
 * {@code SubLevelContainer.removeSubLevel} call is an <i>authorized</i> mirror
 * despawn initiated by our {@link MirrorOps#despawnMirror}.
 *
 * <p>Used in tandem with {@code SableMirrorRemovalGuardMixin} to block all
 * other paths from removing kinematic mirrors. When our despawn path runs, it
 * sets {@link #inAuthorizedRemoval} to {@code true} for the duration of the
 * Sable {@code removeSubLevel} call so the mixin recognises it as legit and
 * passes through.
 *
 * <p>Single boolean is fine because Sable's container ops run on the server
 * thread; the mixin's check and {@code despawnMirror}'s set/clear are on the
 * same thread. If that ever changes (e.g., async sub-level processing), this
 * needs to become a {@link ThreadLocal}.
 */
public final class MirrorRemovalGuard {

    /** Set to {@code true} only inside {@link MirrorOps#despawnMirror}'s removeSubLevel call. */
    public static volatile boolean inAuthorizedRemoval = false;

    private MirrorRemovalGuard() {}
}
