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

    /** ABI level whose piece clipping this Java side speaks ({@link #setPieceSection} etc.). */
    public static final int PIECE_ABI = 3;
    /** Doubles per edge in {@link #setPieceEdges} (ABI 3..4). */
    public static final int EDGE_STRIDE_V3 = 18;
    /** ABI level whose edges carry the label-time plot plane (consistent zone split). */
    public static final int EDGE_PLANE_ABI = 5;
    /** Doubles per edge: the ABI-5 prefix (24), then label-time width/height axes and half extents (8). */
    public static final int EDGE_STRIDE = 32;
    /** ABI 6 carries the complete label-time aperture, for an observed finite sweep. */
    public static final int EDGE_SWEEP_ABI = 6;

    /** Doubles per edge the loaded natives read. */
    public static int nativeEdgeStride() {
        return abiLevel() >= EDGE_SWEEP_ABI ? EDGE_STRIDE : abiLevel() >= EDGE_PLANE_ABI ? 24 : EDGE_STRIDE_V3;
    }

    private static volatile int abiLevel = -1;

    /**
     * Native ABI level (0 = natives not loaded, 1 = a DLL that predates the probe). Probed
     * once; an older DLL simply lacks the symbol, which is caught here instead of surfacing
     * as an {@link UnsatisfiedLinkError} in a physics tick.
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

    /** Whether the loaded natives clip contacts by piece (README, section 9). */
    public static boolean piecesSupported() {
        return abiLevel() >= PIECE_ABI;
    }

    private static native int nativeAbiLevel();

    /**
     * Labels of one 16³ plot section ({@code 4096} bytes, index {@code x | y << 4 | z << 8},
     * unsigned piece ids); {@code null} drops the section (all native).
     */
    public static native void setPieceSection(long sceneHandle, int bodyId, int sx, int sy, int sz, byte[] labels);

    /**
     * Aperture edges of a body: N × {@link #EDGE_STRIDE} doubles
     * {@code [parent child  ox oy oz  nx ny nz  wx wy wz halfW  hx hy hz halfH  zone slack
     * pox poy poz pnx pny pnz pwx pwy pwz phalfW phx phy phz phalfH]}
     * in the parent piece's scene frame, normal towards the parent
     * side; {@code po/pn} is the same plane in plot coordinates when the labels were made
     * (ABI 6 carries the complete reference aperture; ABI 5 receives 24 doubles,
     * earlier libraries receive {@link #EDGE_STRIDE_V3}).
     */
    public static native void setPieceEdges(long sceneHandle, int bodyId, double[] edges);

    /** Piece id (1..255) of an image collider; the native collider is piece 0. */
    public static native void setImageNode(long sceneHandle, int bodyId, long imageHandle, int node);

    /** Drops every piece datum of a body: its contacts are no longer clipped. */
    public static native void clearPieces(long sceneHandle, int bodyId);

    /** ABI 7 resets internal joint warm starts after a common same-chart frame change. */
    public static final int JOINT_TRANSIT_ABI = 7;
    public static native boolean resetGroupJointImpulses(long sceneHandle, int[] bodyIds);

    /** Packed {@code (ropeId << 1) | endBit} for every rope end attached to the body. */
    public static native long[] ropesAttachedToSableBody(long sceneHandle, int bodyId);

    /** ABI 4 can reframe all particles of an eligible same-dimension rope group. */
    public static final int ROPE_TRANSIT_ABI = 4;
    public static native boolean canMoveGroupRopes(long sceneHandle, int[] bodyIds);
    public static native boolean moveGroupRopes(long sceneHandle, int[] bodyIds,
        double tx, double ty, double tz, double qx, double qy, double qz, double qw);

    /**
     * ABI 8: rope points follow portals with their collision geometry (see
     * {@code IplRopeTransit}); the dispatcher also culls voxel pairs that cannot belong to
     * a collider's piece before any manifold is built.
     */
    public static final int ROPE_ROUTE_ABI = 8;
    /** Strand ids owned by the scene view's chart. */
    public static native long[] ropeIds(long sceneHandle);
    /** {@code [x y z]*} of a strand's points (start first); empty when unknown. */
    public static native double[] ropePointPositions(long sceneHandle, long ropeId);
    /**
     * Collide point {@code index} of the strand in {@code destScene}'s chart through the
     * isometry {@code (t, q)}; {@code destScene == 0} restores the strand's own chart.
     */
    public static native boolean routeRopePoint(long sceneHandle, long ropeId, int index, long destScene,
        double tx, double ty, double tz, double qx, double qy, double qz, double qw);

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
     * @return packed collider handle for {@link #removeImageCollider} and
     *         {@link #setImageNode}, or -1 if the body is unknown.
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
}
