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
     * Set (or clear, with an empty array) the infinite-plane clip regions of a body:
     * solver contacts past each region's plane are dropped from the body's manifolds
     * (spec §2.5).
     *
     * <p>Layout: N regions × 14 doubles —
     * {@code [px py pz  nx ny nz  reserved(8)]}. The trailing values are retained for the
     * native ABI and ignored.
     */
    public static native void setClipRegions(long sceneHandle, int bodyId, double[] regions);
}
