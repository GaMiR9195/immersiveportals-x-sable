package ipl.sable.render.route;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplStraddleRenderCache;
import ipl.sable.client.IplStraddleRenderState;
import ipl.sable.client.IplStraddleSessionStore;
import ipl.sable.compat.IplCreateContraptions;
import ipl.sable.render.IplPieceRender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a model hangs: the centre of its holder's block (a block entity's own block, the block a
 * contraption hangs on, the block an entity is in) in the world space of its level, with a body at
 * its REAL pose (never a projection's mapped pose), and the route frame of that block.
 * {@code origin} is where the holder's renderer draws from, in the coordinates of its level. The frame is the piece of the block exactly as the body's terrain is drawn
 * ({@link IplPieceRender#blockEntityPiece}: the server's label carried through the crossings the
 * block's centre made since, at the drawn pose), so a model and the block it stands on are always
 * on the same side; it is never guessed from the side of a plane, which for a two-sided portal
 * would pick the opposite face.
 */
record IplModelAnchor(ClientLevel level, @Nullable ClientSubLevel owner, Matrix4d plotToWorld, BlockPos pos,
					  Vector3d origin, Vector3d point, IplRouteFrame frame) {

	/** Extra slack around a body's swept box: slerp between two poses bulges past both. */
	private static final double SWEEP_SLACK = 0.25;

	/**
	 * The world boxes a renderer may draw in, and how far that geometry moves per tick (0 for the
	 * world): decisions that take effect later (a Flywheel hand-over) lead by it.
	 */
	record ModelBounds(List<AABB> boxes, double lead) {}

	@Nullable
	static IplModelAnchor of(Object holder) {
		if (holder instanceof BlockEntity be) return of(be);
		return holder instanceof Entity entity ? of(entity) : null;
	}

	@Nullable
	private static IplModelAnchor of(BlockEntity be) {
		if (be.isRemoved() || !(be.getLevel() instanceof ClientLevel level)) return null;
		ClientSubLevel owner = Sable.HELPER.getContainingClient(be);
		BlockPos pos = be.getBlockPos();
		return of(level, owner, pos, new Vector3d(pos.getX(), pos.getY(), pos.getZ()));
	}

	@Nullable
	private static IplModelAnchor of(Entity entity) {
		if (entity.isRemoved() || !(entity.level() instanceof ClientLevel level)) return null;
		ClientSubLevel owner = Sable.HELPER.getContaining(entity) instanceof ClientSubLevel sub ? sub : null;
		Vec3 at = entity.getPosition(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false));
		Vec3 holder = IplCreateContraptions.holderPoint(entity);
		if (holder == null) holder = at.add(0.0, entity.getBbHeight() * 0.5, 0.0);
		return of(level, owner, BlockPos.containing(holder), new Vector3d(at.x, at.y, at.z));
	}

	private static IplModelAnchor of(ClientLevel level, @Nullable ClientSubLevel owner, BlockPos pos, Vector3d origin) {
		Matrix4d plotToWorld = owner == null ? IDENTITY : poses(owner).render;
		Vector3d point = plotToWorld.transformPosition(new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
		return new IplModelAnchor(level, owner, plotToWorld, pos, origin, point, frameOf(pos, owner, level));
	}

	/** Draw space of the holder (its renderer's origin) → world space (the pose it is drawn with). */
	Matrix4d holderToWorld() {
		return new Matrix4d(plotToWorld).translate(origin.x, origin.y, origin.z);
	}

	/**
	 * Where the renderer may draw: its bounds as block-space coordinates of the level (for a body,
	 * swept over the body's motion during this tick, so a fast body is caught before its model
	 * reaches an opening) and, for a body, also as plain world coordinates (renderers of links
	 * report the world box of their points), widened by the same motion. Null when the renderer is
	 * unbounded.
	 */
	@Nullable
	ModelBounds modelBounds(Object holder) {
		AABB local;
		double ownMotion = 0.0;
		if (holder instanceof BlockEntity be) {
			BlockEntityRenderer<BlockEntity> renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
			local = renderer == null ? null : renderer.getRenderBoundingBox(be);
			if (local == null) local = new AABB(pos);
		} else if (holder instanceof Entity entity) {
			// An entity moves on its own too (a contraption turning on its bearing, a cart).
			local = entity.getBoundingBoxForCulling();
			ownMotion = entity.position().distanceTo(new Vec3(entity.xo, entity.yo, entity.zo));
		} else {
			return null;
		}
		if (!Double.isFinite(local.minX + local.minY + local.minZ + local.maxX + local.maxY + local.maxZ)) return null;
		if (owner == null) return new ModelBounds(List.of(local.inflate(ownMotion)), ownMotion);
		Poses poses = poses(owner);
		AABB last = IplPortalModel.transform(local, poses.last);
		AABB logical = IplPortalModel.transform(local, poses.logical);
		double lead = Math.max(cornerShift(last, logical), 0.0) + ownMotion;
		AABB swept = IplPortalModel.transform(local, poses.render).minmax(last).minmax(logical)
			.inflate(lead > 0.0 ? lead * SWEEP_SLACK + ownMotion : 0.0);
		// Block entity renderers of links report the world box of their points.
		return holder instanceof BlockEntity
			? new ModelBounds(List.of(swept, local.inflate(lead)), lead)
			: new ModelBounds(List.of(swept), lead);
	}

	/** Upper bound of how far the box moved between two placements. */
	private static double cornerShift(AABB a, AABB b) {
		double dx = Math.max(Math.abs(a.minX - b.minX), Math.abs(a.maxX - b.maxX));
		double dy = Math.max(Math.abs(a.minY - b.minY), Math.abs(a.maxY - b.maxY));
		double dz = Math.max(Math.abs(a.minZ - b.minZ), Math.abs(a.maxZ - b.maxZ));
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** Shared, never mutated. */
	private static final Matrix4d IDENTITY = new Matrix4d();

	/** Plot → world of a body: at its real render pose, and at the two tick poses it lies between. */
	private record Poses(Matrix4d render, Matrix4d last, Matrix4d logical) {}

	/** Poses of each body this render frame (one set per body and frame). */
	private static final Map<ClientSubLevel, Poses> POSES = new IdentityHashMap<>();
	private static long posesFrame = Long.MIN_VALUE;

	private static Poses poses(ClientSubLevel owner) {
		long frame = IplStraddleRenderCache.frameId();
		if (frame != posesFrame) {
			POSES.clear();
			posesFrame = frame;
		}
		return POSES.computeIfAbsent(owner, o -> new Poses(
			realPose(o).bakeIntoMatrix(new Matrix4d()),
			o.lastPose().bakeIntoMatrix(new Matrix4d()),
			o.logicalPose().bakeIntoMatrix(new Matrix4d())));
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
	private static IplRouteFrame frameOf(BlockPos pos, @Nullable ClientSubLevel owner, ClientLevel level) {
		IplRouteFrame root = IplOpenings.root(level);
		IplPieceRender.Frame pieces = owner == null ? null : IplPieceRender.frameFor(owner);
		if (pieces == null) return root;
		int piece = IplPieceRender.blockPiece(pieces, pos);
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
