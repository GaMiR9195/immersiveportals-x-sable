package ipl.sable.client;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.Plane;

/**
 * Render-thread state for drawing a straddling sub-level's DEST-SIDE projection.
 *
 * <p>While set, the sub-level renders with the portal-mapped pose
 * ({@code IplClientSubLevelPoseOverrideMixin}) and the legacy source-clip bracket
 * ({@code SableSourceClipMixin} → {@code SourceClipPortalFinder}) installs the provided
 * complementary plane instead of searching for one — so the projection shows exactly the
 * through-the-portal portion, cut at the mapped portal plane.
 */
public final class IplStraddleRenderState {

    @Nullable private static ClientSubLevel activeSub;
    @Nullable private static Pose3dc activePose;
    @Nullable private static Plane activePlane;
    @Nullable private static Portal activePortal;
    /** Вторая плоскость куска проекции (выход в следующий портал цепочки), или null. */
    @Nullable private static Plane activePlane2;
    private static final java.util.ArrayDeque<State> suspended = new java.util.ArrayDeque<>();
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
        @Nullable ClientSubLevel sub, @Nullable Pose3dc pose,
        @Nullable Plane plane, @Nullable Portal portal, @Nullable Plane plane2
    ) {}

    private IplStraddleRenderState() {}

    public static void set(ClientSubLevel sub, Pose3dc mappedPose, Plane destPlane, Portal portal) {
        set(sub, mappedPose, destPlane, portal, null);
    }

    /** Арм проекции целиком: обе плоскости куска (см. {@link IplClientProjections.StraddleProjection#exclude}). */
    public static void set(IplClientProjections.StraddleProjection projection) {
        set(projection.sub(), projection.mappedPose(), projection.destPlane(),
            projection.portal(), projection.exclude());
    }

    public static void set(
        ClientSubLevel sub, Pose3dc mappedPose, Plane destPlane, Portal portal, @Nullable Plane plane2
    ) {
        // Preserve outer state for recursive portal rendering.
        if (activeSub != null) {
            suspended.addLast(new State(activeSub, activePose, activePlane, activePortal, activePlane2));
        }
        activeSub = sub;
        activePose = mappedPose;
        activePlane = destPlane;
        activePortal = portal;
        activePlane2 = plane2;
    }

    public static void clear() {
        State previous = suspended.pollLast();
        if (previous != null) {
            activeSub = previous.sub();
            activePose = previous.pose();
            activePlane = previous.plane();
            activePortal = previous.portal();
            activePlane2 = previous.plane2();
            return;
        }
        activeSub = null;
        activePose = null;
        activePlane = null;
        activePortal = null;
        activePlane2 = null;
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

    /** Отображённая поза активной проекции НЕЗАВИСИМО от приостановки (для расчёта матриц). */
    @Nullable
    public static Pose3dc getMappedPoseFor(@Nullable Object sub) {
        return isActiveFor(sub) ? activePose : null;
    }

    @Nullable
    public static Plane getPlane2For(@Nullable Object sub) {
        return isActiveFor(sub) ? activePlane2 : null;
    }

    @Nullable
    public static Plane getPlaneFor(@Nullable Object sub) {
        return isActiveFor(sub) ? activePlane : null;
    }

    @Nullable
    public static Portal getPortalFor(@Nullable Object sub) {
        return isActiveFor(sub) ? activePortal : null;
    }

}
