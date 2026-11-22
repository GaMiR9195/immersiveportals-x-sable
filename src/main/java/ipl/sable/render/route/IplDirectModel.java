package ipl.sable.render.route;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;

/**
 * A model wholly in one frame: its renderer draws straight into each pass of that frame's level,
 * placed through the frame's portals. Nothing is recorded or cut, so this costs what the normal
 * draw costs. Used for Flywheel visuals handed to their renderers that are not near enough to an
 * opening to be routed, and for entities whose holder is beyond a portal of a straddling body.
 */
final class IplDirectModel implements IplPortalModel {

	private final Object holder;
	private final IplModelAnchor anchor;
	private final float partialTick;
	private final boolean drawFlywheelParts;

	IplDirectModel(Object holder, IplModelAnchor anchor, float partialTick, boolean drawFlywheelParts) {
		this.holder = holder;
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
		IplModelAnchor.ModelBounds bounds = anchor.modelBounds(holder);
		return bounds == null ? null : IplPortalModel.transform(bounds.boxes().get(0), anchor.frame().toLevel);
	}

	@Override
	public void emit(Level level, MultiBufferSource buffers, Vec3 camera) {
		if (!drawsIn(level)) return;
		Matrix4d worldToDraw = new Matrix4d().translation(-camera.x, -camera.y, -camera.z).mul(anchor.frame().toLevel);
		IplModelCapture.render(holder, anchor, worldToDraw, buffers, partialTick, drawFlywheelParts);
	}
}
