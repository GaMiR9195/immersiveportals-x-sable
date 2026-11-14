package ipl.sable.render.route;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplStraddleRenderCache;
import ipl.sable.client.IplStraddleRenderState;
import ipl.sable.client.IplStraddleSessionStore;
import ipl.sable.render.IplPieceRender;
import ipl.sable.transit.IplPieceMap;
import ipl.sable.transit.IplProjectionTree;
import ipl.sable.transit.IplStraddlePoseMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Block-entity models near the opening of a straddling body: ropes, springs, chains and
 * connectors between bodies, and the body's own models that reach its opening. Their renderers
 * compute them in the real chart, so whatever reaches the passed material would run on behind
 * the entrance.
 *
 * <p>The rule is the one of the bodies themselves, without any plane: a part of a model is
 * where the path to it from the point the model hangs on leads, and a path goes through only
 * where it passes an opening ({@link IplLinkTopology}). Every block-entity draw near an active
 * opening is recorded whole ({@link IplModelRecorder}) and written with the parts of its route:
 * <ul>
 *   <li>a normal draw writes the parts that went through no opening;</li>
 *   <li>the projection draw of a straddling body (its piece beyond an edge, at the mapped pose)
 *       writes the parts of its own block entities that went through that edge;</li>
 *   <li>the pass that draws that piece also runs the renderers of OTHER bodies' and the
 *       world's models at the portal-mapped pose ({@link #drawImages}) and writes the parts
 *       that went through.</li>
 * </ul>
 * The state at the anchor is the piece of the holder's block, so either end of a link may be
 * the holder. All draws read the current state with every body at its real pose: nothing
 * lags, nothing is drawn twice, a link keeps both of its ends. Render thread only.
 */
public final class IplGeometryRouting {

	/** A model counts as near an aperture this far ahead, so its image is ready before it crosses. */
	private static final double NEAR_MARGIN = 2.0;
	/** States are bit masks over the openings of one draw. */
	private static final int MAX_OPENINGS = 30;

	/** One entry aperture of a straddling body, in world space at the body's real pose. */
	private record Aperture(ClientSubLevel body, IplPieceMap.Edge edge, Vector3d origin, Vector3d normal,
							Vector3d u, double halfU, Vector3d v, double halfV, AABB box) {

		IplPortalRect rect(@Nullable Matrix4d portal, Vector3d camera) {
			return IplPortalRect.of(origin, normal, u, halfU, v, halfV, portal, camera);
		}

		/** The same portal opening (apertures of several bodies passing one portal). */
		boolean sameOpening(Aperture o) {
			return origin.distanceSquared(o.origin) < 1.0e-4 && normal.dot(o.normal) > 0.999;
		}
	}

	/** World bounds of a model ({@code null}: unbounded) and the point it hangs on (its block centre). */
	private record Bounds(@Nullable AABB box, Vector3d anchor) {

		boolean reaches(Aperture a) {
			return box == null || a.box().intersects(box);
		}
	}

	/** How a model is written in the current draw (states: bit {@code k} = through opening {@code k}). */
	private record Route(List<IplPortalRect> openings, Vector3d anchor, int anchorState, int wanted) {}

	private static final Map<Level, List<Aperture>> APERTURES = new IdentityHashMap<>();
	/** Models of other bodies and of the world seen near an aperture: candidates for the image draws. */
	private static final Set<BlockEntity> NEAR = Collections.newSetFromMap(new WeakHashMap<>());
	private static long cacheFrame = Long.MIN_VALUE;
	private static long cacheGeneration = Long.MIN_VALUE;
	private static int imageDraws;

	private IplGeometryRouting() {}

	// ------------------------------------------------------------------ every block-entity draw

	/** A block-entity draw: {@code draw} renders {@code be} into the given buffers. */
	public static void render(BlockEntity be, MultiBufferSource buffers, Consumer<MultiBufferSource> draw) {
		Route route = route(be);
		if (route == null) {
			draw.accept(buffers);
			return;
		}
		IplModelRecorder model = new IplModelRecorder();
		draw.accept(model);
		model.replay(buffers, route.openings(), route.anchor(), route.anchorState(), route.wanted());
	}

	@Nullable
	private static Route route(BlockEntity be) {
		if (imageDraws > 0 || be.getLevel() == null) return null;
		List<Aperture> apertures = apertures(be.getLevel());
		if (apertures.isEmpty()) return null;
		ClientSubLevel owner = owner(be);
		if (owner != null && IplStraddleRenderState.isActiveFor(owner)) return projectionRoute(be, owner, apertures);
		if (IplStraddleRenderState.isActive()) return null;
		return normalRoute(be, owner, apertures);
	}

	/** A draw at the real pose: keeps what went through no opening. */
	@Nullable
	private static Route normalRoute(BlockEntity be, @Nullable ClientSubLevel owner, List<Aperture> apertures) {
		Bounds bounds = bounds(be, owner);
		List<Aperture> near = new ArrayList<>(1);
		for (Aperture a : apertures) {
			if (near.size() == MAX_OPENINGS || !bounds.reaches(a)) continue;
			if (near.stream().noneMatch(o -> o.sameOpening(a))) near.add(a);
		}
		boolean imaged = false;
		for (Aperture a : near) imaged |= ownEdge(owner, a, apertures) == null;
		if (imaged) NEAR.add(be);
		else NEAR.remove(be);
		if (near.isEmpty()) return null;
		Vector3d camera = cameraPosition();
		List<IplPortalRect> openings = new ArrayList<>(near.size());
		int state = 0;
		for (int k = 0; k < near.size(); k++) {
			openings.add(near.get(k).rect(null, camera));
			IplPieceMap.Edge edge = ownEdge(owner, near.get(k), apertures);
			if (edge != null && IplPieceRender.blockEntityPiece(IplPieceRender.frameFor(owner), be) == edge.child) {
				state |= 1 << k;
			}
		}
		return new Route(openings, new Vector3d(bounds.anchor()).sub(camera), state, 0);
	}

	/** The projection draw of {@code owner}'s piece beyond an edge: keeps what went through that edge. */
	@Nullable
	private static Route projectionRoute(BlockEntity be, ClientSubLevel owner, List<Aperture> apertures) {
		IplPieceRender.Frame frame = IplPieceRender.frameFor(owner);
		if (frame == null) return null;
		int node = IplStraddleRenderState.getNodeFor(owner);
		Aperture aperture = null;
		for (Aperture a : apertures) if (a.body() == owner && a.edge().child == node) aperture = a;
		IplStraddlePoseMap.StraddleMapping mapping = frame.mappingOf(node);
		if (aperture == null || mapping == null) return null;
		Vec3 centre = Vec3.atCenterOf(be.getBlockPos());
		Vector3d real = frame.pose.transformPosition(new Vector3d(centre.x, centre.y, centre.z));
		Bounds bounds = bounds(be, owner);
		if (!bounds.reaches(aperture)) return null;
		Matrix4d portal = portalMatrix(mapping);
		Vector3d camera = cameraPosition();
		int state = IplPieceRender.blockEntityPiece(frame, be) == node ? 1 : 0;
		return new Route(List.of(aperture.rect(portal, camera)), portal.transformPosition(real).sub(camera), state, 1);
	}

	/** The edge through which {@code owner} itself straddles the opening of {@code a}, if any. */
	@Nullable
	private static IplPieceMap.Edge ownEdge(@Nullable ClientSubLevel owner, Aperture a, List<Aperture> apertures) {
		if (owner == null) return null;
		for (Aperture own : apertures) if (own.body() == owner && own.sameOpening(a)) return own.edge();
		return null;
	}

	// ------------------------------------------------------------------ images of other models

	/** Whether {@code body} has models of others to draw through its apertures this frame. */
	public static boolean hasImages(ClientSubLevel body) {
		if (apertures(body.getLevel()).stream().noneMatch(a -> a.body() == body)) return false;
		for (BlockEntity be : NEAR) if (be.getLevel() == body.getLevel()) return true;
		return false;
	}

	/**
	 * In the pass that draws {@code body}'s piece {@code node} beyond {@code edge}: draws the parts
	 * of the world's and other bodies' models that went through that opening, at the
	 * portal-mapped pose. Bodies straddling the same opening draw their own parts in their
	 * projection draws.
	 */
	public static void drawImages(ClientSubLevel body, IplPieceRender.Frame frame, int node, IplPieceMap.Edge edge) {
		if (node != edge.child) return;
		List<Aperture> apertures = apertures(body.getLevel());
		Aperture aperture = null;
		for (Aperture a : apertures) if (a.body() == body && a.edge() == edge) aperture = a;
		IplStraddlePoseMap.StraddleMapping mapping = frame.mappingOf(node);
		if (aperture == null || mapping == null) return;
		Matrix4d portal = portalMatrix(mapping);
		Vector3d camera = cameraPosition();
		List<IplPortalRect> openings = List.of(aperture.rect(portal, camera));
		float partialTick = RenderStates.getPartialTick();
		MultiBufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
		imageDraws++;
		// Every body answers with its real pose: the portal is applied to the whole model.
		IplStraddleRenderState.suspendPose();
		try {
			for (BlockEntity be : new ArrayList<>(NEAR)) {
				if (be.isRemoved() || be.getLevel() != body.getLevel()) continue;
				ClientSubLevel owner = owner(be);
				if (owner == body || ownEdge(owner, aperture, apertures) != null) continue;
				Bounds bounds = bounds(be, owner);
				if (!bounds.reaches(aperture)) continue;
				Vector3d anchor = portal.transformPosition(new Vector3d(bounds.anchor())).sub(camera);
				IplModelRecorder model = new IplModelRecorder();
				drawMapped(be, owner, portal, camera, partialTick, model);
				model.replay(buffers, openings, anchor, 0, 1);
			}
		} finally {
			IplStraddleRenderState.resumePose();
			imageDraws--;
		}
	}

	private static void drawMapped(BlockEntity be, @Nullable ClientSubLevel owner, Matrix4d portal, Vector3d camera,
								   float partialTick, MultiBufferSource buffers) {
		BlockEntityRenderer<BlockEntity> renderer =
			Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
		if (renderer == null || be.getLevel() == null) return;
		Pose3dc pose = owner == null ? new Pose3d() : owner.renderPose();
		BlockPos pos = be.getBlockPos();
		Matrix4d model = new Matrix4d().translation(-camera.x, -camera.y, -camera.z)
			.mul(portal)
			.mul(pose.bakeIntoMatrix(new Matrix4d()))
			.translate(pos.getX(), pos.getY(), pos.getZ());
		PoseStack poseStack = new PoseStack();
		poseStack.mulPose(new Matrix4f(model));
		renderer.render(be, partialTick, poseStack, buffers, LevelRenderer.getLightColor(be.getLevel(), pos),
			OverlayTexture.NO_OVERLAY);
	}

	private static Matrix4d portalMatrix(IplStraddlePoseMap.StraddleMapping mapping) {
		return mapping.mapPose(new Pose3d()).bakeIntoMatrix(new Matrix4d());
	}

	// ------------------------------------------------------------------ Flywheel

	public static boolean hasApertures(Level level) {
		return !apertures(level).isEmpty();
	}

	/**
	 * Whether the model of {@code be}, owned by a body that does not straddle, comes within
	 * {@code lead} of an active aperture: Flywheel must give it to its block-entity renderer.
	 */
	public static boolean isNearAperture(ClientSubLevel owner, BlockEntity be, double lead) {
		List<Aperture> apertures = apertures(owner.getLevel());
		if (apertures.isEmpty()) return false;
		AABB ownerBox = owner.boundingBox().toMojang().inflate(lead + NEAR_MARGIN);
		if (apertures.stream().noneMatch(a -> a.box().intersects(ownerBox))) return false;
		AABB box = bounds(be, owner).box();
		if (box == null) return true;
		AABB reach = box.inflate(lead);
		for (Aperture a : apertures) if (a.box().intersects(reach)) return true;
		return false;
	}

	// ------------------------------------------------------------------ apertures

	private static List<Aperture> apertures(Level level) {
		long frame = IplStraddleRenderCache.frameId();
		long generation = IplProjectionTree.generation();
		if (frame != cacheFrame || generation != cacheGeneration) {
			cacheFrame = frame;
			cacheGeneration = generation;
			APERTURES.clear();
		}
		if (IplStraddleSessionStore.isIdle()) return List.of();
		return APERTURES.computeIfAbsent(level, IplGeometryRouting::collect);
	}

	private static List<Aperture> collect(Level level) {
		if (!(SubLevelContainer.getContainer(level) instanceof ClientSubLevelContainer container)) return List.of();
		List<Aperture> out = new ArrayList<>();
		for (ClientSubLevel body : container.getAllSubLevels()) {
			IplPieceRender.Frame frame = IplPieceRender.frameFor(body);
			if (frame == null) continue;
			for (IplPieceMap.Edge e : frame.splitEdges(IplPieceMap.NATIVE)) {
				if (e.parent == IplPieceMap.NATIVE) out.add(aperture(body, frame.pose, e));
			}
		}
		return out;
	}

	private static Aperture aperture(ClientSubLevel body, Pose3dc pose, IplPieceMap.Edge e) {
		IplPieceMap.Aperture a = e.aperture;
		Vector3d origin = pose.transformPosition(new Vector3d(a.ox(), a.oy(), a.oz()));
		Vector3d normal = pose.transformNormal(new Vector3d(a.nx(), a.ny(), a.nz())).normalize();
		Vector3d u = pose.transformNormal(new Vector3d(a.wx(), a.wy(), a.wz())).normalize();
		Vector3d v = pose.transformNormal(new Vector3d(a.hx(), a.hy(), a.hz())).normalize();
		AABB box = null;
		for (int i = 0; i < 4; i++) {
			double su = (i & 1) == 0 ? -a.halfW() : a.halfW();
			double sv = (i & 2) == 0 ? -a.halfH() : a.halfH();
			Vector3d c = new Vector3d(u).mul(su).add(origin).fma(sv, v);
			AABB point = new AABB(c.x, c.y, c.z, c.x, c.y, c.z);
			box = box == null ? point : box.minmax(point);
		}
		return new Aperture(body, e, origin, normal, u, a.halfW(), v, a.halfH(), box.inflate(NEAR_MARGIN));
	}

	// ------------------------------------------------------------------ model geometry

	@Nullable
	private static ClientSubLevel owner(BlockEntity be) {
		return Sable.HELPER.getContainingClient(be);
	}

	private static Bounds bounds(BlockEntity be, @Nullable ClientSubLevel owner) {
		Vec3 centre = Vec3.atCenterOf(be.getBlockPos());
		Vector3d anchor = world(new Vector3d(centre.x, centre.y, centre.z), owner);
		BlockEntityRenderer<BlockEntity> renderer =
			Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
		AABB local = renderer == null ? null : renderer.getRenderBoundingBox(be);
		if (local == null || !Double.isFinite(local.minX + local.minY + local.minZ + local.maxX + local.maxY + local.maxZ)) {
			return new Bounds(null, anchor);
		}
		AABB box = null;
		for (int i = 0; i < 8; i++) {
			Vector3d c = world(new Vector3d((i & 1) == 0 ? local.minX : local.maxX,
				(i & 2) == 0 ? local.minY : local.maxY, (i & 4) == 0 ? local.minZ : local.maxZ), owner);
			AABB point = new AABB(c.x, c.y, c.z, c.x, c.y, c.z);
			box = box == null ? point : box.minmax(point);
		}
		return new Bounds(box, anchor);
	}

	private static Vector3d world(Vector3d local, @Nullable ClientSubLevel owner) {
		return owner == null ? local : owner.renderPose().transformPosition(local);
	}

	private static Vector3d cameraPosition() {
		Vec3 camera = CHelper.getCurrentCameraPos();
		return new Vector3d(camera.x, camera.y, camera.z);
	}
}
