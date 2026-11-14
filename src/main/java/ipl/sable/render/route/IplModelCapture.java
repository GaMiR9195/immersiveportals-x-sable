package ipl.sable.render.route;

import com.mojang.blaze3d.vertex.PoseStack;
import ipl.sable.client.IplProjectionLevelScope;
import ipl.sable.client.IplStraddleRenderState;
import ipl.sable.render.IplSubLevelBlockEntityPass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Runs a block entity's renderer outside the normal block-entity loop, independent of the pass
 * being drawn: at its anchor's real pose placed by a given matrix, with {@code Minecraft.level}
 * set to its own level (renderers that look up partners through it find them) and, when
 * Flywheel does not draw it, with Flywheel reported unsupported (renderers then draw the parts
 * Flywheel would instance).
 */
final class IplModelCapture {

	private static final Logger LOG = LoggerFactory.getLogger("ipl-portal-models");
	private static final Set<BlockEntityType<?>> FAILED = new HashSet<>();
	private static int depth;

	private IplModelCapture() {}

	/** True while a renderer runs here: nested block-entity draws go where it writes, as they are. */
	static boolean isCapturing() {
		return depth > 0;
	}

	/** Records the model in route space (absolute world coordinates of its level); null on failure. */
	@Nullable
	static IplModelRecorder record(BlockEntity be, IplModelAnchor anchor, float partialTick, boolean drawFlywheelParts) {
		Vector3d origin = anchor.point();
		IplModelRecorder recorder = new IplModelRecorder(origin);
		Matrix4d toOrigin = new Matrix4d().translation(-origin.x, -origin.y, -origin.z);
		return render(be, anchor, toOrigin, recorder, partialTick, drawFlywheelParts) ? recorder : null;
	}

	/**
	 * Draws the model straight into {@code out}; {@code worldToDraw} places the world space of the
	 * anchor's level into the space of the draw (a frame's map and the camera offset).
	 */
	static boolean render(BlockEntity be, IplModelAnchor anchor, Matrix4d worldToDraw, MultiBufferSource out,
						  float partialTick, boolean drawFlywheelParts) {
		BlockEntityRenderer<BlockEntity> renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
		if (renderer == null) return false;
		PoseStack pose = new PoseStack();
		pose.mulPose(new Matrix4f(new Matrix4d(worldToDraw).mul(anchor.blockToWorld())));
		int light = LevelRenderer.getLightColor(anchor.level(), anchor.pos());
		Runnable draw = () -> renderer.render(be, partialTick, pose, out, light, OverlayTexture.NO_OVERLAY);
		depth++;
		IplStraddleRenderState.suspendPose();
		try {
			IplProjectionLevelScope.runInLevel(anchor.level(),
				drawFlywheelParts ? () -> IplSubLevelBlockEntityPass.runForcingVanilla(draw) : draw);
			return true;
		} catch (RuntimeException e) {
			if (FAILED.add(be.getType())) LOG.warn("Portal routing could not draw {}; it is drawn unrouted", be.getType(), e);
			return false;
		} finally {
			IplStraddleRenderState.resumePose();
			depth--;
		}
	}
}
