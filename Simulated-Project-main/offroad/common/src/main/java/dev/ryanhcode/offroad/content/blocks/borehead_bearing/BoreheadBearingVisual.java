package dev.ryanhcode.offroad.content.blocks.borehead_bearing;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.foundation.render.AllInstanceTypes;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.AbstractInstance;
import dev.engine_room.flywheel.lib.instance.FlatLit;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.OrientedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.createmod.catnip.data.Iterate;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.function.Consumer;

public class BoreheadBearingVisual extends KineticBlockEntityVisual<BoreheadBearingBlockEntity> implements SimpleDynamicVisual {

    private final EnumMap<Direction, RotatingInstance> instanceMap = new EnumMap<>(Direction.class);

    private final OrientedInstance topInstance;

    public BoreheadBearingVisual(final VisualizationContext context, final BoreheadBearingBlockEntity be, final float partialTick) {
        super(context, be, partialTick);

        final Instancer<RotatingInstance> halfShaftInstancer = this.instancerProvider().instancer(AllInstanceTypes.ROTATING, Models.partial(AllPartialModels.SHAFT_HALF));

        final BlockState state = be.getBlockState();
        final Direction.Axis axis = ((BoreheadBearingBlock) state.getBlock()).getRotationAxis(state);
        for (final Direction dir : Iterate.directionsInAxis(axis)) {
            final RotatingInstance rotInstance = halfShaftInstancer.createInstance();
            rotInstance.setup(be, axis, be.getSpeed() * dir.getAxisDirection().getStep())
                    .setPosition(this.getVisualPosition())
                    .rotateToFace(Direction.SOUTH, dir)
                    .setChanged();

            this.instanceMap.put(dir, rotInstance);
        }

        this.topInstance = this.instancerProvider().instancer(InstanceTypes.ORIENTED, Models.partial((AllPartialModels.BEARING_TOP)))
                .createInstance();

        this.topInstance.position(this.getVisualPosition())
                .rotateTo(Direction.UP, this.blockState.getValue(BlockStateProperties.FACING))
                .setChanged();
    }

    @Override
    public void update(final float partialTick) {
        this.instanceMap.forEach((dir, instance) -> {
            instance.setup(this.blockEntity, dir.getAxis(), this.blockEntity.getSpeed() * dir.getAxisDirection().getStep())
                    .setChanged();
        });
    }

    @Override
    public void beginFrame(final Context context) {
        this.topInstance
                .identityRotation()
                .rotateTo(Direction.UP, this.blockState.getValue(BlockStateProperties.FACING))
                .rotateDegrees(this.blockState.getValue(BlockStateProperties.FACING).getAxisDirection().getStep() * this.blockEntity.getInterpolatedAngle(context.partialTick() - 1), Direction.Axis.Y)
                .setChanged();
    }

    @Override
    public void updateLight(final float v) {
        this.relight(this.instanceMap.values().toArray(FlatLit[]::new));
        this.relight(this.topInstance);
    }

    @Override
    public void collectCrumblingInstances(final Consumer<@Nullable Instance> consumer) {
        this.instanceMap.values().forEach(consumer);
        consumer.accept(this.topInstance);
    }

    @Override
    protected void _delete() {
        this.instanceMap.values().forEach(AbstractInstance::delete);
        this.instanceMap.clear();

        this.topInstance.delete();
    }
}
