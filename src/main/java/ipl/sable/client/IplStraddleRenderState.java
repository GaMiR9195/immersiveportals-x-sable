package ipl.sable.client;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.render.IplPieceRender;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;

/**
 * Render-thread state for drawing one image (projection) of a straddling sub-level.
 *
 * <p>While set, the sub-level renders with the image's mapped pose ({@code ClientSubLevelMixin})
 * and its piece draws decide per section what belongs to the image's piece
 * ({@code IplPieceRender}).
 *
 * <p>The {@link #suspendPose} / {@link #resumePose} mechanism lets BERs obtain the REAL
 * (native, frame-captured) pose for coordinate transforms like
 * {@code renderPose().transformPositionInverse()}. Without this, the BER would receive the
 * mapped pose (already baked into the PoseStack) and double-apply the portal transformation.
 *
 * <p>When suspended, {@link #getPoseFor} returns the {@link #realPose} captured at frame time
 * rather than null. Returning null would fall through to the live {@code sub.renderPose()},
 * which may differ from the frame-time snapshot due to Sable's continuous interpolation and
 * cause springs/ropes to jump to the wrong coordinates for one frame when sessions start.
 */
public final class IplStraddleRenderState {

	@Nullable private static ClientSubLevel activeSub;
	@Nullable private static Pose3dc activePose;
	/** Piece of the armed image ({@code -1}: unknown, draw whole). */
	private static int activeNode = -1;
	private static final ArrayDeque<State> suspended = new ArrayDeque<>();

	/**
	 * Frame-time captured REAL pose of the sub-level, stored when {@link #set} arms a
	 * projection. Used by {@link #getPoseFor} while {@link #poseSuspendDepth} &gt; 0 so
	 * BERs (ropes, springs) that call {@code renderPose().transformPositionInverse()} get
	 * the same pose that was used to build the frame's aperture geometry.
	 */
	@Nullable private static Pose3dc realPose;

	/**
	 * Глубина «приостановки» подмены позы. Пока &gt; 0, {@link #getPoseFor} отдаёт
	 * {@link #realPose} (кадровый снимок настоящей позы), а кусок проекции остаётся выбранным.
	 *
	 * <p>Зачем: матрица проекции (mapped pose) уже вшита в PoseStack, который Sable
	 * передаёт в BER. Но многие BER (верёвки Simulated, пружины, клей) сами переводят
	 * МИРОВЫЕ точки в локальные координаты через {@code renderPose().transformPositionInverse}.
	 * С настоящей (кадровой) позой локальная точка верная, а PoseStack проекции переносит её
	 * ровно туда, где находится кусок.
	 *
	 * <p>Используется живой {@link Pose3dc} из {@code sub.renderPose()}, который Sable
	 * непрерывно интерполирует. Если вернуть null (старое поведение), ClientSubLevelMixin
	 * откатится к актуальному {@code sub.renderPose()}, который к этому моменту уже может
	 * отличаться от позы начала кадра — отсюда прыжок пружины на один кадр при открытии сессии.
	 */
	private static int poseSuspendDepth;

	private record State(@Nullable ClientSubLevel sub, @Nullable Pose3dc pose,
	                     @Nullable Pose3dc real, int node) {}

	private IplStraddleRenderState() {}

	/** Arms a whole projection: its piece at its mapped pose and the frame's real pose. */
	public static void set(IplClientProjections.StraddleProjection projection) {
		// Supply the frame-captured real pose so BERs (ropes, springs) that call
		// renderPose().transformPositionInverse() during suspendPose() get the same stable
		// snapshot that was used to build aperture geometry, not the live interpolated value.
		IplPieceRender.Frame frame = IplPieceRender.frameFor(projection.sub());
		Pose3dc realPoseSnapshot = frame != null ? frame.pose : null;
		set(projection.sub(), projection.mappedPose(), projection.node(), realPoseSnapshot);
	}

	/**
	 * Arms a projection with an explicitly supplied real (native) pose. The real pose is used
	 * when {@link #suspendPose()} is active so BERs get a stable, frame-consistent coordinate
	 * origin rather than the live interpolated {@code sub.renderPose()}.
	 *
	 * @param realPoseSnapshot the frame-time snapshot of the sub-level's native pose, or
	 *     {@code null} to fall back to the live pose (pre-existing behaviour)
	 */
	public static void set(ClientSubLevel sub, Pose3dc mappedPose, int node,
	                       @Nullable Pose3dc realPoseSnapshot) {
		// Preserve outer state for recursive portal rendering.
		if (activeSub != null) {
			suspended.addLast(new State(activeSub, activePose, realPose, activeNode));
		}
		activeSub = sub;
		activePose = mappedPose;
		realPose = realPoseSnapshot;
		activeNode = node;
	}

	public static void clear() {
		State previous = suspended.pollLast();
		activeSub = previous == null ? null : previous.sub();
		activePose = previous == null ? null : previous.pose();
		realPose = previous == null ? null : previous.real();
		activeNode = previous == null ? -1 : previous.node();
	}

	/** Начать участок, где BER должен видеть НАСТОЯЩУЮ (кадровую) позу. */
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

	/**
	 * Returns the pose override for {@code sub}.
	 *
	 * <ul>
	 *   <li>When <em>not</em> suspended: returns the mapped pose so the sub-level draws at the
	 *       projection location.</li>
	 *   <li>When suspended ({@link #poseSuspendDepth} &gt; 0): returns the frame-captured real
	 *       pose so BERs get a stable, non-interpolated coordinate origin. If no real pose was
	 *       recorded (legacy call path), returns {@code null} to fall through to
	 *       {@code sub.renderPose()}.</li>
	 * </ul>
	 */
	@Nullable
	public static Pose3dc getPoseFor(@Nullable Object sub) {
		if (!isActiveFor(sub)) return null;
		if (poseSuspendDepth > 0) return realPose; // null → live pose fallback in ClientSubLevelMixin
		return activePose;
	}

	/** Piece of the image armed for {@code sub}; {@code -1} when none or unknown. */
	public static int getNodeFor(@Nullable Object sub) {
		return isActiveFor(sub) ? activeNode : -1;
	}
}
