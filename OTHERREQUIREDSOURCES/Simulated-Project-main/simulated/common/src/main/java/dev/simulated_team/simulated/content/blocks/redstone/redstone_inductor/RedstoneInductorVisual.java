package dev.simulated_team.simulated.content.blocks.redstone.redstone_inductor;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.OrientedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.simulated_team.simulated.index.SimPartialModels;
import dev.simulated_team.simulated.util.SimColors;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

public class RedstoneInductorVisual extends AbstractBlockEntityVisual<RedstoneInductorBlockEntity> implements SimpleDynamicVisual {

    private final OrientedInstance redstoneIndicator;

    public RedstoneInductorVisual(final VisualizationContext ctx, final RedstoneInductorBlockEntity blockEntity, final float partialTick) {
        super(ctx, blockEntity, partialTick);

        this.redstoneIndicator = this.instancerProvider().instancer(InstanceTypes.ORIENTED, Models.partial(SimPartialModels.REDSTONE_INDUCTOR_INDICATOR))
                .createInstance();

        this.redstoneIndicator
                .position(this.getVisualPosition())
                .translatePosition(0.5f, 0, 0.5f)
                .translatePivot(-0.5f, 0, -0.5f)
                .rotateYDegrees(AngleHelper.horizontalAngle(blockEntity.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING)));

        this.redstoneIndicator.colorArgb(SimColors.redstone(this.blockEntity.lerpedState.getValue(partialTick) / 15f));
    }

    @Override
    public void beginFrame(final Context context) {
        this.redstoneIndicator.colorArgb(SimColors.redstone(this.blockEntity.lerpedState.getValue(context.partialTick()) / 15f));
        this.redstoneIndicator.setChanged();
    }

    @Override
    public void collectCrumblingInstances(final Consumer<@Nullable Instance> consumer) {
        consumer.accept(this.redstoneIndicator);
    }

    @Override
    public void updateLight(final float v) {
        this.relight(this.redstoneIndicator);
    }

    @Override
    protected void _delete() {
        this.redstoneIndicator.delete();
    }
}
