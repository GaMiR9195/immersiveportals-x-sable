package ipl.sable.transit;

/**
 * Static flag marking that the {@code allocateSubLevel} call currently executing
 * on the server thread is spawning a kinematic mirror.
 *
 * <p><b>Why this is needed:</b> a mirror must NOT be enrolled in the physics
 * pipeline (it has no native rigid body -- its pose is driven entirely by the
 * portal mapping). Enrollment happens inside
 * {@code SubLevelContainer.allocateSubLevel} via the
 * {@code SubLevelPhysicsSystem.onSubLevelAdded} observer, which fires
 * <em>before</em> {@code allocateSubLevel} returns -- i.e. before
 * {@code MirrorOps.spawnMirror} can set the per-instance
 * {@link ipl.sable.iface.IplKinematicSubLevelHolder} flag. So at
 * {@code onSubLevelAdded} time the instance flag is still {@code false} and a
 * flag-based skip would fire too late.
 *
 * <p>This guard bridges that gap: {@code MirrorOps.spawnMirror} sets it
 * {@code true} for the duration of the mirror's {@code allocateSubLevel} call,
 * and {@code SableMirrorPhysicsSystemMixin.onSubLevelAdded} reads it to decide
 * whether to skip {@code pipeline.add} (and build the mass tracker directly
 * instead). Same single-thread, set/clear-in-finally pattern as
 * {@link MirrorRemovalGuard}.
 *
 * <p>Single boolean is safe because Sable's container ops run on the server
 * thread; the set/clear in {@code spawnMirror} and the read in the observer are
 * the same synchronous call stack. If sub-level allocation ever becomes async,
 * this must become a {@link ThreadLocal}.
 */
public final class MirrorAllocationGuard {

    /** Set {@code true} only inside {@code MirrorOps.spawnMirror}'s allocateSubLevel call. */
    public static volatile boolean allocatingMirror = false;

    private MirrorAllocationGuard() {}
}
