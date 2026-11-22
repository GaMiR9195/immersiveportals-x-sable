package ipl.sable.render.route;

import com.mojang.blaze3d.vertex.PoseStack;
import ipl.sable.client.IplProjectionLevelScope;
import ipl.sable.client.IplStraddleRenderState;
import ipl.sable.render.IplSubLevelBlockEntityPass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Runs a holder's renderer (a block entity's or an entity's) outside the normal render loops,
 * independent of the pass being drawn: at its anchor's real pose placed by a given matrix, with
 * {@code Minecraft.level} set to its own level (renderers that look up partners through it find
 * them) and, when Flywheel does not draw it, with Flywheel reported unsupported (renderers then
 * draw the parts Flywheel would instance: a contraption's whole structure).
 */
final class IplModelCapture {

	private static final Logger LOG = LoggerFactory.getLogger("ipl-portal-models");
	private static final Set<String> FAILED = new HashSet<>();
	private static int depth;

	private IplModelCapture() {}

	/** True while a renderer runs here: nested draws go where it writes, as they are. */
	static boolean isCapturing() {
		return depth > 0;
	}

	/**
	 * Records the model in route space (world coordinates of its level, relative to the anchor)
	 * into {@code recorder}, which is reset first; null on failure.
	 */
	@Nullable
	static IplModelRecorder record(Object holder, IplModelAnchor anchor, float partialTick, boolean drawFlywheelParts,
								   IplModelRecorder recorder) {
		Vector3d origin = anchor.point();
		recorder.reset(origin);
		Matrix4d toOrigin = new Matrix4d().translation(-origin.x, -origin.y, -origin.z);
		return render(holder, anchor, toOrigin, recorder, partialTick, drawFlywheelParts) ? recorder : null;
	}

	/**
	 * Draws the model straight into {@code out}; {@code worldToDraw} places the world space of the
	 * anchor's level into the space of the draw (a frame's map and the camera offset).
	 */
	static boolean render(Object holder, IplModelAnchor anchor, Matrix4d worldToDraw, MultiBufferSource out,
						  float partialTick, boolean drawFlywheelParts) {
		PoseStack pose = new PoseStack();
		pose.mulPose(new Matrix4f(new Matrix4d(worldToDraw).mul(anchor.holderToWorld())));
		Runnable draw = drawOf(holder, anchor, pose, out, partialTick);
		if (draw == null) return false;
		depth++;
		IplStraddleRenderState.suspendPose();
		try {
			IplProjectionLevelScope.runInLevel(anchor.level(),
				drawFlywheelParts ? () -> IplSubLevelBlockEntityPass.runForcingVanilla(draw) : draw);
			return true;
		} catch (RuntimeException e) {
			if (FAILED.add(IplModelHolders.type(holder))) {
				LOG.warn("Portal routing could not draw {}; it is drawn unrouted", IplModelHolders.type(holder), e);
			}
			return false;
		} finally {
			IplStraddleRenderState.resumePose();
			depth--;
		}
	}

	@Nullable
	private static Runnable drawOf(Object holder, IplModelAnchor anchor, PoseStack pose, MultiBufferSource out,
								   float partialTick) {
		if (holder instanceof BlockEntity be) {
			BlockEntityRenderer<BlockEntity> renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
			if (renderer == null) return null;
			int light = LevelRenderer.getLightColor(anchor.level(), anchor.pos());
			return () -> renderer.render(be, partialTick, pose, out, light, OverlayTexture.NO_OVERLAY);
		}
		if (holder instanceof Entity entity) {
			EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
			EntityRenderer<? super Entity> renderer = dispatcher.getRenderer(entity);
			float yaw = Mth.lerp(partialTick, entity.yRotO, entity.getYRot());
			return () -> {
				Vec3 offset = renderer.getRenderOffset(entity, partialTick);
				pose.translate(offset.x, offset.y, offset.z);
				renderer.render(entity, yaw, partialTick, pose, out, dispatcher.getPackedLightCoords(entity, partialTick));
			};
		}
		return null;
	}
}
