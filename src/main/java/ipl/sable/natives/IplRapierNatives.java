package ipl.sable.natives;

/**
 * IPSable's extensions to the sable_rapier native surface (portal-physics spec phase 4).
 * The symbols live in the SAME DLL as Sable's natives (our local build — see
 * {@code IplNativesOverrideMixin} and {@code natives/build-windows.ps1}), namespaced under
 * this class so sable's own JNI ABI stays untouched.
 *
 * <p>Callers MUST check {@link #isAvailable()} first: when the custom natives failed to
 * load (non-Windows platform, kill switch, extraction error) these methods throw
 * {@link UnsatisfiedLinkError}.
 */
public final class IplRapierNatives {

    private static volatile boolean available = false;

    private IplRapierNatives() {}

    /** Set by {@code IplNativesOverrideMixin} once the IPSable-built DLL is loaded. */
    public static void markAvailable() {
        available = true;
    }

    public static boolean isAvailable() {
        return available;
    }

    /**
     * Set (or clear, with an empty array) the aperture clip regions of a body: solver
     * contacts past the region's plane and within its lateral rectangle are dropped from
     * the body's manifolds (spec §2.5).
     *
     * <p>Layout: N regions × 14 doubles —
     * {@code [px py pz  nx ny nz  wx wy wz  halfW  hx hy hz  halfH]}.
     */
    public static native void setClipRegions(long sceneHandle, int bodyId, double[] regions);

    /**
     * Give a body private voxel section storage, detached from the scene-wide chunk map.
     * Subsequent body-targeted {@code addChunk} feeds store sections in the body's own
     * {@code chunk_map} (the storage native kinematic contraptions already use), and
     * {@code removeSubLevel} frees them with the body — no {@code removeChunk} needed.
     *
     * <p>Required for straddle clone bodies through same-dimension portals: clone and real
     * body share one scene while describing identical ship-local section coordinates, so
     * shared storage lets one body's uploads or cleanup corrupt the other's collision data.
     * Call immediately after the clone's native {@code createSubLevel}, before any chunk feed.
     */
    public static native void useDedicatedChunks(long sceneHandle, int bodyId);

    /**
     * Register ({@code excluded=true}) or clear a contact exclusion between two bodies in
     * one scene: the dispatcher's dynamic-vs-dynamic path generates no contact manifolds
     * for excluded pairs (and drops persisted ones). Used for a straddle clone vs its own
     * real body — and clone↔clone of one ship — when a same-dimension portal puts them in
     * the same scene, where they would otherwise phantom-collide.
     *
     * <p>Idempotent in both directions; no-op on a null scene or negative/equal ids.
     */
    public static native void setBodyPairExclusion(
        long sceneHandle, int idA, int idB, boolean excluded);

    /**
     * Diagnostics readback for the aperture clip pass: fills {@code out} (length >= 5)
     * with {@code [contactsSeen, contactsDropped, lastContactX, lastContactY,
     * lastContactZ]} for the body. Counters accumulate since body creation. No-op on a
     * null scene or unknown body ({@code out} left untouched).
     */
    public static native void getClipStats(long sceneHandle, int bodyId, double[] out);

    // ------------------------------------------------------------------
    // Atlas M2 (spec v3 §2.2-2.3): image colliders — Tier-1 exact coupling.
    // ------------------------------------------------------------------

    /**
     * Create an image collider for the body in the CALLING scene view's chart, portal
     * isometry {@code P = (R, t)}: translation {@code (dx,dy,dz)} plus rotation quat
     * {@code (qx,qy,qz,qw)} (identity for translation-only portals). The image is extra
     * geometry on the SAME rigid body: its contacts act on the body through the engine's
     * portal-frame mapping — exact, in-solver, no servo, any fixed rotation (Tier 2).
     *
     * @return packed collider handle for {@link #removeImageCollider} /
     *         {@link #setImageClipRegions}, or -1 if the body is unknown.
     */
    public static native long createImageCollider(
        long sceneHandle, int bodyId,
        double dx, double dy, double dz,
        double qx, double qy, double qz, double qw);

    /**
     * Atlas M5: update an image collider's portal isometry — moving portals (animated,
     * or anchored to physics structures) re-derive {@code P = (R, t)} per tick.
     */
    public static native void setImagePrefix(
        long sceneHandle, long imageHandle,
        double dx, double dy, double dz,
        double qx, double qy, double qz, double qw);

    /** Remove an image collider created by {@link #createImageCollider}. */
    public static native void removeImageCollider(
        long sceneHandle, int bodyId, long imageHandle);

    /**
     * Set (or clear, with an empty array) the clip regions of one IMAGE collider — the
     * far side of the half-open aperture seam. Same 14-double layout as
     * {@link #setClipRegions}.
     */
    public static native void setImageClipRegions(
        long sceneHandle, int bodyId, long imageHandle, double[] regions);
}
