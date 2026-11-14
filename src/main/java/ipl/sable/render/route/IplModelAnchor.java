package ipl.sable.render.route;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplStraddleRenderCache;
import ipl.sable.client.IplStraddleRenderState;
import ipl.sable.client.IplStraddleSessionStore;
import ipl.sable.render.IplPieceRender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the model of a block entity hangs: the centre of its block in the world space of its
 * level, with a body at its REAL pose (never a projection's mapped pose), and the route frame of
 * that block. A block of a straddling body that went through a portal is in its piece's frame,
 * so either end of a link may be the one that holds the model.
 */
record IplModelAnchor(ClientLevel level, @Nullable ClientSubLevel owner, Matrix4d plotToWorld, BlockPos pos,
					  Vector3d point, IplRouteFrame frame) {

	@Nullable
	static IplModelAnchor of(BlockEntity be) {
		if (be.isRemoved() || !(be.getLevel() instanceof ClientLevel level)) return null;
		ClientSubLevel owner = Sable.HELPER.getContainingClient(be);
		Matrix4d plotToWorld = owner == null ? IDENTITY : plotToWorld(owner);
		BlockPos pos = be.getBlockPos();
		Vector3d point = plotToWorld.transformPosition(new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
		IplRouteFrame frame = frameOf(be, owner, level);
		if (owner != null) frame = settle(frame, point);
		return new IplModelAnchor(level, owner, plotToWorld, pos, point, frame);
	}

	/** Block space of the block entity → world space (the pose a renderer draws it with). */
	Matrix4d blockToWorld() {
		return new Matrix4d(plotToWorld).translate(pos.getX(), pos.getY(), pos.getZ());
	}

	/**
	 * World boxes the renderer may draw in: its bounds as block-space coordinates of the level
	 * (placed by the body pose) and, for a body, also as plain world coordinates (renderers of
	 * links report the world box of their points). Null when the renderer is unbounded.
	 */
	@Nullable
	List<AABB> modelBoxes(BlockEntity be) {
		BlockEntityRenderer<BlockEntity> renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
		AABB local = renderer == null ? null : renderer.getRenderBoundingBox(be);
		if (local == null) local = new AABB(pos);
		if (!Double.isFinite(local.minX + local.minY + local.minZ + local.maxX + local.maxY + local.maxZ)) return null;
		if (owner == null) return List.of(local);
		return List.of(transform(local, plotToWorld), local);
	}

	private static AABB transform(AABB box, Matrix4d m) {
		double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
		double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
		Vector3d c = new Vector3d();
		for (int i = 0; i < 8; i++) {
			m.transformPosition((i & 1) == 0 ? box.minX : box.maxX, (i & 2) == 0 ? box.minY : box.maxY,
				(i & 4) == 0 ? box.minZ : box.maxZ, c);
			minX = Math.min(minX, c.x); minY = Math.min(minY, c.y); minZ = Math.min(minZ, c.z);
			maxX = Math.max(maxX, c.x); maxY = Math.max(maxY, c.y); maxZ = Math.max(maxZ, c.z);
		}
		return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
	}

	/** Shared, never mutated. */
	private static final Matrix4d IDENTITY = new Matrix4d();
	/** Plot → world of each body this render frame (one pose per body and frame). */
	private static final Map<ClientSubLevel, Matrix4d> POSES = new IdentityHashMap<>();
	private static long posesFrame = Long.MIN_VALUE;

	private static Matrix4d plotToWorld(ClientSubLevel owner) {
		long frame = IplStraddleRenderCache.frameId();
		if (frame != posesFrame) {
			POSES.clear();
			posesFrame = frame;
		}
		return POSES.computeIfAbsent(owner, o -> realPose(o).bakeIntoMatrix(new Matrix4d()));
	}

	/** The body pose of this frame's native draw (the piece frame's snapshot when it straddles). */
	private static Pose3dc realPose(ClientSubLevel owner) {
		IplPieceRender.Frame frame = IplPieceRender.frameFor(owner);
		if (frame != null) return frame.pose;
		IplStraddleRenderState.suspendPose();
		try {
			return new Pose3d(owner.renderPose());
		} finally {
			IplStraddleRenderState.resumePose();
		}
	}

	/**
	 * A body block lying across an opening's plane is drawn split by that plane (its piece's
	 * label decides only away from the plane), so the model hanging on it takes the side of its
	 * anchor point: in front of the entry → the frame before it; behind an opening of its frame →
	 * the frame beyond it. A label that flips while the block lies on the plane then moves
	 * nothing, and the anchor changes frame exactly when it crosses, like the geometry.
	 */
	private static IplRouteFrame settle(IplRouteFrame frame, Vector3d p) {
		if (frame.parent != null && frame.entry != null) {
			IplRouteRect entry = frame.parent.rectOf(frame.entry);
			if (inZone(entry, p) && entry.side(p.x, p.y, p.z) >= 0.0) return frame.parent;
		}
		for (IplOpening opening : IplOpenings.of(frame.level)) {
			IplRouteRect rect = frame.rectOf(opening);
			if (!inZone(rect, p) || rect.side(p.x, p.y, p.z) >= 0.0) continue;
			IplRouteFrame next = frame.through(opening);
			if (next != null && next != frame.parent) return next;
		}
		return frame;
	}

	/** Within a block's reach of the plane, over the opening. */
	private static boolean inZone(IplRouteRect rect, Vector3d p) {
		double side = rect.side(p.x, p.y, p.z);
		if (Math.abs(side) > ZONE) return false;
		return rect.containsOnPlane(p.x - rect.normal.x * side, p.y - rect.normal.y * side, p.z - rect.normal.z * side);
	}

	/** Half the diagonal of a block: a block whose centre is this close may lie across the plane. */
	private static final double ZONE = 0.87;

	/** The frame of the block's piece: the root, or the portal path of the session that holds it. */
	private static IplRouteFrame frameOf(BlockEntity be, @Nullable ClientSubLevel owner, ClientLevel level) {
		IplRouteFrame root = IplOpenings.root(level);
		IplPieceRender.Frame pieces = owner == null ? null : IplPieceRender.frameFor(owner);
		if (pieces == null) return root;
		int piece = IplPieceRender.blockEntityPiece(pieces, be);
		if (piece == pieces.view.nativeNode()) return root;
		for (IplStraddleSessionStore.ResolvedSession session : pieces.view.sessions()) {
			if (session.node() == piece) return follow(root, session.path());
		}
		return root;
	}

	private static IplRouteFrame follow(IplRouteFrame root, List<Portal> path) {
		IplRouteFrame frame = root;
		for (Portal portal : path) {
			IplOpening opening = IplOpenings.byId(frame.level, portal.getUUID());
			if (opening == null) opening = IplOpening.of(portal);
			IplRouteFrame next = opening == null ? null : frame.through(opening);
			if (next == null) return frame;
			frame = next;
		}
		return frame;
	}
}
