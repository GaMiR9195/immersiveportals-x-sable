package dev.eriksonn.aeronautics.content.blocks.mounted_potato_cannon;

import com.mojang.math.Axis;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.content.kinetics.base.ShaftVisual;
import com.simibubi.create.foundation.render.AllInstanceTypes;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.OrientedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.eriksonn.aeronautics.index.AeroPartialModels;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.joml.Quaternionf;

import java.util.function.Consumer;

public class MountedPotatoCannonVisual extends ShaftVisual<MountedPotatoCannonBlockEntity> implements SimpleDynamicVisual {

	private final OrientedInstance cogInstance;
	final Axis rotationAxis;
	final Quaternionf blockOrientation;

	public MountedPotatoCannonVisual(final VisualizationContext context, final MountedPotatoCannonBlockEntity blockEntity, final float partialTick) {
		super(context, blockEntity, partialTick);

		final Direction facing = this.blockState.getValue(BlockStateProperties.FACING);
        this.rotationAxis = Axis.of(Direction.get(Direction.AxisDirection.POSITIVE, this.rotationAxis()).step());

        this.blockOrientation = getBlockStateOrientation(facing);

		this.cogInstance = this.instancerProvider().instancer(InstanceTypes.ORIENTED, Models.partial(AeroPartialModels.CANNON_COG))
				.createInstance();

		this.cogInstance.position(this.getVisualPosition())
				.rotation(this.blockOrientation)
				.setChanged();
	}

	@Override
	public void update(final float pt) {
		super.update(pt);
	}

	@Override
	public void updateLight(final float partialTick) {
		super.updateLight(partialTick);
		this.relight(this.cogInstance);
	}

	@Override
	protected void _delete() {
		super._delete();
		this.cogInstance.delete();
	}

	@Override
	public void collectCrumblingInstances(final Consumer<Instance> consumer) {
		super.collectCrumblingInstances(consumer);
		consumer.accept(this.cogInstance);
	}

	static Quaternionf getBlockStateOrientation(final Direction facing) {
		final Quaternionf orientation;

		if (facing.getAxis().isHorizontal()) {
			orientation = Axis.YP.rotationDegrees(AngleHelper.horizontalAngle(facing));
		} else {
			orientation = new Quaternionf();
		}

		orientation.mul(Axis.XP.rotationDegrees(AngleHelper.verticalAngle(facing)));
		return orientation;
	}

	@Override
	public void beginFrame(final DynamicVisual.Context context) {
		final float angle = this.blockEntity.getCogwheelAngle(context.partialTick());
		final Quaternionf rot = Axis.ZP.rotationDegrees(angle);
		rot.premul(this.blockOrientation);
        this.cogInstance.rotation(rot)
				.setChanged();
	}
}