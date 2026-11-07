package ipl.sable.client;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;

/**
 * Render-thread state for drawing one image (projection) of a straddling sub-level.
 *
 * <p>While set, the sub-level renders with the image's mapped pose ({@code ClientSubLevelMixin})
 * and its piece draws decide per section what belongs to the image's piece
 * ({@code IplPieceRender}).
 */
public final class IplStraddleRenderState {

    @Nullable private static ClientSubLevel activeSub;
    @Nullable private static Pose3dc activePose;
    /** Piece of the armed image ({@code -1}: unknown, draw whole). */
    private static int activeNode = -1;
    private static final ArrayDeque<State> suspended = new ArrayDeque<>();
    /**
     * Глубина «приостановки» подмены позы. Пока &gt; 0, {@link #getPoseFor} отдаёт null,
     * т.е. {@code renderPose()} снова возвращает НАСТОЯЩУЮ позу саб-уровня, а кусок проекции
     * остаётся выбранным.
     *
     * <p>Зачем: матрица проекции (mapped pose) уже вшита в PoseStack, который Sable
     * передаёт в BER. Но многие BER (верёвки Simulated, пружины, клей) сами переводят
     * МИРОВЫЕ точки в локальные координаты через {@code renderPose().transformPositionInverse}.
     * Если в этот момент renderPose() подменён на отображённую позу, мировая точка
     * источника переводится «не той» позой, и после матрицы проекции верёвка рисуется
     * в исходных мировых координатах внутри ЧУЖОГО измерения. С настоящей позой локальная
     * точка верная, а PoseStack проекции переносит её ровно туда, где находится кусок.
     */
    private static int poseSuspendDepth;

    private record State(@Nullable ClientSubLevel sub, @Nullable Pose3dc pose, int node) {}

    private IplStraddleRenderState() {}

    /** Arms a whole projection: its piece at its mapped pose. */
    public static void set(IplClientProjections.StraddleProjection projection) {
        set(projection.sub(), projection.mappedPose(), projection.node());
    }

    public static void set(ClientSubLevel sub, Pose3dc mappedPose, int node) {
        // Preserve outer state for recursive portal rendering.
        if (activeSub != null) suspended.addLast(new State(activeSub, activePose, activeNode));
        activeSub = sub;
        activePose = mappedPose;
        activeNode = node;
    }

    public static void clear() {
        State previous = suspended.pollLast();
        activeSub = previous == null ? null : previous.sub();
        activePose = previous == null ? null : previous.pose();
        activeNode = previous == null ? -1 : previous.node();
    }

    /** Начать участок, где BER должен видеть настоящую позу (см. {@link #poseSuspendDepth}). */
    public static void suspendPose() {
        poseSuspendDepth++;
    }

    public static void resumePose() {
        if (poseSuspendDepth > 0) poseSuspendDepth--;
    }

    public static boolean isActive() {
        return activeSub != null;
    }

    public static boolean isActiveFor(@Nullable Object sub) {
        return activeSub != null && activeSub == sub;
    }

    @Nullable
    public static Pose3dc getPoseFor(@Nullable Object sub) {
        return poseSuspendDepth == 0 && isActiveFor(sub) ? activePose : null;
    }

    /** Piece of the image armed for {@code sub}; {@code -1} when none or unknown. */
    public static int getNodeFor(@Nullable Object sub) {
        return isActiveFor(sub) ? activeNode : -1;
    }
}
