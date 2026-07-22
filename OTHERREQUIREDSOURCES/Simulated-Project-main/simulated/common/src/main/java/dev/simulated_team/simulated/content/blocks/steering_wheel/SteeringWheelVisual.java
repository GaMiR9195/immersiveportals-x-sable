package dev.simulated_team.simulated.content.blocks.steering_wheel;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.foundation.render.AllInstanceTypes;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.ColoredLitInstance;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.model.baked.BakedModelBuilder;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.simulated_team.simulated.index.SimPartialModels;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class SteeringWheelVisual extends KineticBlockEntityVisual<SteeringWheelBlockEntity> implements SimpleDynamicVisual {
    private static final RendererReloadCache<SteeringWheelRenderer.ModelKey, Model> MODEL_CACHE = new RendererReloadCache<>(SteeringWheelVisual::generateModel);

    private final RotatingInstance halfShaftInstance;

    private final Direction facing;
    private final boolean onFloor;

    private TransformedInstance wheelInstance;
    private BlockState lastMaterial;

    private final List<ColoredLitInstance> allInstances = new ArrayList<>();


    public SteeringWheelVisual(final VisualizationContext ctx, final SteeringWheelBlockEntity blockEntity, final float partialTick) {
        super(ctx, blockEntity, partialTick);


        this.facing = blockEntity.getBlockState().getValue(SteeringWheelBlock.FACING);
        this.onFloor = blockEntity.getBlockState().getValue(SteeringWheelBlock.ON_FLOOR);

        this.setupWheel(partialTick);

        this.halfShaftInstance = this.instancerProvider().instancer(AllInstanceTypes.ROTATING, Models.partial(AllPartialModels.SHAFT_HALF))
                .createInstance().setup(blockEntity, Direction.Axis.X)
                .rotateToFace(this.onFloor ? Direction.SOUTH : Direction.NORTH) //what
                .setPosition(this.getVisualPosition());

        this.allInstances.add(this.halfShaftInstance);
    }

    @Override
    public void update(final float partialTick) {
        super.update(partialTick);

        this.halfShaftInstance.setup(this.blockEntity, Direction.Axis.Y)
                .setChanged();
    }

    @Override
    public void beginFrame(final Context context) {
        if (this.blockEntity.material != this.lastMaterial) {
            this.wheelInstance.delete();
            this.allInstances.remove(this.wheelInstance);

            this.setupWheel(context.partialTick());
        } else {
            this.transformWheel(context.partialTick());
        }
    }

    private void setupWheel(final float partialTicks) {
        this.lastMaterial = this.blockEntity.material;
        this.wheelInstance = this.instancerProvider().instancer(InstanceTypes.TRANSFORMED, MODEL_CACHE.get(new SteeringWheelRenderer.ModelKey(this.lastMaterial)))
                .createInstance();

        this.transformWheel(partialTicks);

        this.allInstances.add(this.wheelInstance);
        this.relight(this.wheelInstance);
        this.wheelInstance.setChanged();
    }

    private void transformWheel(final float partialTicks) {
        this.wheelInstance.setIdentityTransform();

        this.wheelInstance.translate(this.getVisualPosition())
                .rotateCentered(this.facing.getRotation());

        if (this.onFloor) {
            this.wheelInstance.translate(0, 6.5 / 16f, -5 / 16f);
        } else {
            this.wheelInstance.translate(0, 6.5 / 16f, 5 / 16f);
        }

        this.wheelInstance.rotateCentered(this.blockEntity.getRenderAngle(partialTicks), Direction.UP);
        this.wheelInstance.setChanged();
    }

    @Override
    public void collectCrumblingInstances(final Consumer<@Nullable Instance> consumer) {
        for (final ColoredLitInstance inst : this.allInstances) {
            consumer.accept(inst);
        }
    }

    @Override
    public void updateLight(final float v) {
        for (final ColoredLitInstance inst : this.allInstances) {
            this.relight(inst);
        }
    }

    @Override
    protected void _delete() {
        for (final ColoredLitInstance inst : this.allInstances) {
            inst.delete();
        }
    }

    private static Model generateModel(final SteeringWheelRenderer.ModelKey modelKey) {
        final BakedModel bakedModel = SteeringWheelRenderer.generateModel(SimPartialModels.STEERING_WHEEL.get(), modelKey.material());
        return new BakedModelBuilder(bakedModel).build();
    }
}
