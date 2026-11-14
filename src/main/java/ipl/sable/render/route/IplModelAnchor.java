package ipl.sable.render.route;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
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

import java.util.List;

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
		Matrix4d plotToWorld = owner == null ? new Matrix4d() : realPose(owner).bakeIntoMatrix(new Matrix4d());
		BlockPos pos = be.getBlockPos();
		Vector3d point = plotToWorld.transformPosition(new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
		return new IplModelAnchor(level, owner, plotToWorld, pos, point, frameOf(be, owner, level));
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
