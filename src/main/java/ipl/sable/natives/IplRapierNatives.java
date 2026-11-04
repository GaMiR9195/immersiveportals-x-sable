package ipl.sable.natives;

/**
 * IPSable's extensions to the sable_rapier native surface.
 * The symbols live in the SAME library as Sable's natives (IPSable's build from
 * {@code natives/}, loaded by {@code Rapier3DMixin}), namespaced under this class so
 * Sable's own JNI ABI stays untouched.
 *
 * <p>Callers MUST check {@link #isAvailable()} first: when the custom natives are not
 * loaded (no IPSable build for the platform, kill switch, extraction error) these methods
 * throw {@link UnsatisfiedLinkError}.
 */
public final class IplRapierNatives {

    private static volatile boolean available = false;

    private IplRapierNatives() {}

    /** Set by {@code Rapier3DMixin} once the IPSable-built DLL is loaded. */
    public static void markAvailable() {
        available = true;
    }

    public static boolean isAvailable() {
        return available;
    }

    /** Region mode values of the ABI-2 payload (17th double of each region). */
    public static final double REGION_EXCLUDE = 0.0;
    public static final double REGION_KEEP = 1.0;
    /** Doubles per region in the ABI-2 payload. */
    public static final int REGION_STRIDE_V2 = 17;

    private static volatile int abiLevel = -1;

    /**
     * Native ABI level: 1 = exclusion-only clip regions (natives that predate the probe),
     * 2 = keep/exclude regions ({@link #setClipRegionsV2}, {@link #setImageClipRegionsV2}).
     * Probed once; an older DLL simply lacks the symbol, which is caught here instead of
     * surfacing as an {@link UnsatisfiedLinkError} in a physics tick.
     */
    public static int abiLevel() {
        int level = abiLevel;
        if (level >= 0) return level;
        if (!available) return 0;
        try {
            level = Math.max(1, nativeAbiLevel());
        } catch (UnsatisfiedLinkError e) {
            level = 1;
        }
        abiLevel = level;
        return level;
    }

    private static native int nativeAbiLevel();

    /**
     * ABI 2 body clip regions: N regions × 17 doubles —
     * {@code [px py pz  nx ny nz  wx wy wz halfW  hx hy hz halfH  skin maxDepth  mode]}.
     * A contact is neutralized when it lies inside any EXCLUDE region or outside any KEEP
     * region (KEEP regions intersect).
     */
    public static native void setClipRegionsV2(long sceneHandle, int bodyId, double[] regions);

    /** ABI 2 image clip regions (same 17-double layout as {@link #setClipRegionsV2}). */
    public static native void setImageClipRegionsV2(
        long sceneHandle, int bodyId, long imageHandle, double[] regions);

    /**
     * Set (or clear, with an empty array) body clip regions: solver contacts past the
     * region's plane and within its lateral rectangle are dropped from its manifolds.
     * Infinite lateral half extents encode a full plane.
     *
     * <p>Layout: N regions × 14 doubles —
     * {@code [px py pz  nx ny nz  wx wy wz  halfW  hx hy hz  halfH]}.
     */
    public static native void setClipRegions(long sceneHandle, int bodyId, double[] regions);

    /**
     * Sable bodies in {@code bodyId}'s joint component. {@code rigidOnly} restricts the
     * walk to joints whose both endpoints are ship bodies AND at least one angular
     * axis is locked (the current native definition of a rigid assembly: bearings,
     * fixed/prismatic couplings). Ball joints, springs and rope particle chains do
     * not bridge components in this mode. This flag is not an all-joints traversal.
     */
    public static native int[] connectedSableBodyIds(
        long sceneHandle, int bodyId, boolean rigidOnly);

    /** Packed {@code (ropeId << 1) | endBit} for every rope end attached to the body. */
    public static native long[] ropesAttachedToSableBody(long sceneHandle, int bodyId);

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
