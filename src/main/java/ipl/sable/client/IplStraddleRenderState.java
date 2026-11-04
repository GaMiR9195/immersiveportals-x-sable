package ipl.sable.client;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.Plane;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Render-thread state for drawing a straddling sub-level's DEST-SIDE projection.
 *
 * <p>While set, the sub-level renders with the portal-mapped pose
 * ({@code ClientSubLevelMixin}) and the clip brackets ({@code ChunkedRenderDataMixin} →
 * {@code IplClipPortalFinder}) install the projection's own convex cell instead of the native
 * one — so the projection shows exactly its piece of the body.
 */
public final class IplStraddleRenderState {

    @Nullable private static ClientSubLevel activeSub;
    @Nullable private static Pose3dc activePose;
    /** Convex clip cell of the armed piece (intersection of kept half-spaces). */
    @Nullable private static List<Plane> activePlanes;
    @Nullable private static Portal activePortal;
    private static final ArrayDeque<State> suspended = new ArrayDeque<>();
    /**
     * Глубина «приостановки» подмены позы. Пока &gt; 0, {@link #getPoseFor} отдаёт null,
     * т.е. {@code renderPose()} снова возвращает НАСТОЯЩУЮ позу саб-уровня, а плоскости
     * отсечения проекции остаются активными.
     *
     * <p>Зачем: матрица проекции (mapped pose) уже вшита в PoseStack, который Sable
     * передаёт в BER. Но многие BER (верёвки Simulated, пружины, клей) сами переводят
     * МИРОВЫЕ точки в локальные координаты через {@code renderPose().transformPositionInverse}.
     * Если в этот момент renderPose() подменён на отображённую позу, мировая точка
     * источника переводится «не той» позой, и после матрицы проекции верёвка рисуется
     * в исходных мировых координатах внутри ЧУЖОГО измерения — это и были «копии верёвок
     * на фиксированных местах дальше». С настоящей позой локальная точка верная, а
     * PoseStack проекции переносит её ровно туда, где находится кусок проекции.
     */
    private static int poseSuspendDepth;

    private record State(
        @Nullable ClientSubLevel sub, @Nullable Pose3dc pose, @Nullable List<Plane> planes, @Nullable Portal portal
    ) {}

    private IplStraddleRenderState() {}

    /** Arms a whole projection: its mapped pose and its cell. */
    public static void set(IplClientProjections.StraddleProjection projection) {
        set(projection.sub(), projection.mappedPose(), projection.planes(), projection.portal());
    }

    public static void set(ClientSubLevel sub, Pose3dc mappedPose, List<Plane> planes, Portal portal) {
        // Preserve outer state for recursive portal rendering.
        if (activeSub != null) {
            suspended.addLast(new State(activeSub, activePose, activePlanes, activePortal));
        }
        activeSub = sub;
        activePose = mappedPose;
        activePlanes = planes;
        activePortal = portal;
    }

    public static void clear() {
        State previous = suspended.pollLast();
        if (previous != null) {
            activeSub = previous.sub();
            activePose = previous.pose();
            activePlanes = previous.planes();
            activePortal = previous.portal();
            return;
        }
        activeSub = null;
        activePose = null;
        activePlanes = null;
        activePortal = null;
    }

    /** Начать участок, где BER должен видеть настоящую позу (см. {@link #poseSuspendDepth}). */
    public static void suspendPose() {
        poseSuspendDepth++;
    }

    public static void resumePose() {
        if (poseSuspendDepth > 0) poseSuspendDepth--;
    }

    public static boolean isActiveFor(@Nullable Object sub) {
        return activeSub != null && activeSub == sub;
    }

    @Nullable
    public static Pose3dc getPoseFor(@Nullable Object sub) {
        return poseSuspendDepth == 0 && isActiveFor(sub) ? activePose : null;
    }

    /** The armed piece's cell, or null when no projection of {@code sub} is armed. */
    @Nullable
    public static List<Plane> getPlanesFor(@Nullable Object sub) {
        return isActiveFor(sub) ? activePlanes : null;
    }

    @Nullable
    public static Portal getPortalFor(@Nullable Object sub) {
        return isActiveFor(sub) ? activePortal : null;
    }
}
