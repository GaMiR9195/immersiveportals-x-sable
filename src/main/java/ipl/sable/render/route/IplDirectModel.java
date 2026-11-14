package ipl.sable.render.route;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;

import java.util.List;

/**
 * A model wholly in one frame: its renderer draws straight into each pass of that frame's level,
 * placed through the frame's portals. Nothing is recorded or cut, so this costs what the normal
 * draw costs. Used for models near an opening that do not reach across it, and for Flywheel
 * visuals handed to their renderers.
 */
final class IplDirectModel implements IplPortalModel {

	private final BlockEntity be;
	private final IplModelAnchor anchor;
	private final float partialTick;
	private final boolean drawFlywheelParts;

	IplDirectModel(BlockEntity be, IplModelAnchor anchor, float partialTick, boolean drawFlywheelParts) {
		this.be = be;
		this.anchor = anchor;
		this.partialTick = partialTick;
		this.drawFlywheelParts = drawFlywheelParts;
	}

	@Override
	public boolean drawsIn(Level level) {
		return anchor.frame().level == level;
	}

	@Override
	@Nullable
	public AABB boundsIn(Level level) {
		List<AABB> boxes = anchor.modelBoxes(be);
		return boxes == null ? null : IplPortalModel.transform(boxes.get(0), anchor.frame().toLevel);
	}

	@Override
	public void emit(Level level, MultiBufferSource buffers, Vec3 camera) {
		if (!drawsIn(level)) return;
		Matrix4d worldToDraw = new Matrix4d().translation(-camera.x, -camera.y, -camera.z).mul(anchor.frame().toLevel);
		IplModelCapture.render(be, anchor, worldToDraw, buffers, partialTick, drawFlywheelParts);
	}
}
