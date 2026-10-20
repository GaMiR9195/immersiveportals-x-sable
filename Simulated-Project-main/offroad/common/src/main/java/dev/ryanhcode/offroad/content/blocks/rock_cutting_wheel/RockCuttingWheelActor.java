package dev.ryanhcode.offroad.content.blocks.rock_cutting_wheel;

import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.render.ActorVisual;
import com.simibubi.create.content.contraptions.render.ContraptionMatrices;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.ryanhcode.offroad.content.blocks.borehead_bearing.BoreheadBearingBlockEntity;
import dev.ryanhcode.offroad.content.entities.BoreheadContraptionEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelHelper;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import static net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING;

public class RockCuttingWheelActor implements MovementBehaviour {

    public static final double SEARCH_ORIGIN_OFFSET = 1;

    private static boolean reverseRotation(final Direction.Axis bearingAxis, final float bearingSpeed, final Direction localDir, final boolean axisAlongFirst, final BlockPos localPos) {
        final Direction.Axis rotationAxis; // axis of the "shaft"
        if (localDir.getAxis().isVertical()) {
            rotationAxis = axisAlongFirst ? Direction.Axis.Z : Direction.Axis.X;
        } else {
            final boolean facingUp = axisAlongFirst == (localDir.getStepX() == 0);
            rotationAxis = facingUp ? Direction.Axis.Y : localDir.getClockWise().getAxis();
        }

        if (localDir.getAxis() == bearingAxis) {
            return switch (rotationAxis) {
                case Z -> {
                    if (localPos.getZ() == 0) {
                        // roll towards center
                        if (localDir.getAxis() == Direction.Axis.X) {
                            yield ((localPos.getY() > 0) == (bearingSpeed > 0));
                        } else {
                            yield (localPos.getX() > 0) != (bearingSpeed > 0);
                        }
                    }
                    // roll along rotation
                    yield !(localPos.getZ() > 0);
                }
                case Y -> {
                    if (localPos.getY() == 0) {
                        // roll towards center
                        if (localDir.getAxis() == Direction.Axis.Z) {
                            yield ((localDir.getStepZ() > 0) == (localPos.getX() > 0)) != (bearingSpeed > 0);
                        } else {
                            yield ((localDir.getStepX() > 0) == (localPos.getZ() > 0)) == (bearingSpeed > 0);
                        }
                    }
                    // roll along rotation
                    yield (localPos.getY() > 0) != (localDir.getStepX() < 0 || localDir.getStepZ() < 0);
                }
                case X -> {
                    if (localPos.getX() == 0) {
                        // roll towards center
                        if (localDir.getAxis() == Direction.Axis.Y) {
                            yield ((localDir.getStepY() > 0) == (localPos.getZ() > 0)) != (bearingSpeed > 0);
                        } else {
                            yield ((localPos.getY() > 0)) == (bearingSpeed > 0);
                        }
                    }
                    // roll along rotation
                    yield (localPos.getX() > 0) != (localDir == Direction.DOWN);
                }
            };
        }

        if (bearingAxis == rotationAxis) {
            return false;
        }

        // TODO: the war never ends (rotation direction for outwards pointing wheels)
        return false;
    }

    @Override
    public void startMoving(final MovementContext context) {
    }

    @Override
    public void tick(final MovementContext context) {
        if (context.world.isClientSide && context.temporaryData == null) {
            context.temporaryData = LerpedFloat.angular();
        }

        final BlockPos controllerPos = ((BoreheadContraptionEntity) context.contraption.entity).getControllerPos();
        final BlockEntity be = context.world.getBlockEntity(controllerPos);
        if (be instanceof final BoreheadBearingBlockEntity bhbe) {
            final boolean meetsSpeed = Math.abs(bhbe.getSpeed()) > 0.1;
            if (!context.world.isClientSide) {
                //apparently context.data.contraption.entity does not exist when we start moving...
                if (!context.data.contains("Initialized")) {
                    final int newIndex = bhbe.requestNewIndexAndIncrement(context);
                    if (newIndex != -1) {
                        context.data.putInt("Index", newIndex);
                    }

                    context.data.putBoolean("Initialized", true);
                }

                if (meetsSpeed && !bhbe.isStalled()) {
                    final BlockPos pos = context.localPos;
                    final BlockState state = context.state;

                    Vec3 centerPos = pos.getCenter();
                    final Vec3i facingNormal = state.getValue(BlockStateProperties.FACING).getNormal();
                    centerPos = centerPos.add(facingNormal.getX() * SEARCH_ORIGIN_OFFSET, facingNormal.getY() * SEARCH_ORIGIN_OFFSET, facingNormal.getZ() * SEARCH_ORIGIN_OFFSET);

                    final Vec3 contraptionProjectedPos = context.contraption.entity.toGlobalVector(centerPos, 1);
                    final Vec3 sublevelProjected = Sable.HELPER.projectOutOfSubLevel(context.world, contraptionProjectedPos);

                    bhbe.updatePosition(context.data.getInt("Index"), sublevelProjected);
                }
            } else {
                final LerpedFloat lerpingObject = (LerpedFloat) context.temporaryData;

                if (lerpingObject != null) {
                    float clientRotationSpeed = bhbe.getRotationSpeed() * 8;

                    final BlockState state = context.state;
                    final boolean reversed = reverseRotation(
                            ((BoreheadContraptionEntity) context.contraption.entity).getRotationAxis(),
                            ((BoreheadContraptionEntity) context.contraption.entity).getAngleDelta(),
                            state.getValue(FACING),
                            state.getValue(RockCuttingWheelBlock.AXIS_ALONG_FIRST_COORDINATE),
                            context.localPos);

                    if (reversed) {
                        clientRotationSpeed *= -1;
                    }

                    final double rate = 0.3;
                    if (!bhbe.isStalled() && meetsSpeed && !bhbe.isSlowingDown()) {
                        lerpingObject.chase(lerpingObject.getValue() + clientRotationSpeed, rate, LerpedFloat.Chaser.EXP);
                    }

                    lerpingObject.tickChaser();
                }
            }
        }
    }

    @Override
    public boolean disableBlockEntityRendering() {
        return true;
    }

    @Override
    public void renderInContraption(final MovementContext context, final VirtualRenderWorld renderWorld, final ContraptionMatrices matrices, final MultiBufferSource buffer) {
        if (renderWorld.supportsVisualization()) {
            return;
        }

        if (context.temporaryData == null) {
            context.temporaryData = LerpedFloat.angular();
        }

        RockCuttingWheelRenderer.renderInContraption(context, renderWorld, matrices, buffer);
    }

    @Override
    public @Nullable ActorVisual createVisual(final VisualizationContext visualizationContext, final VirtualRenderWorld simulationWorld, final MovementContext context) {
        if (context.temporaryData == null) {
            context.temporaryData = LerpedFloat.angular();
        }

        return new RockCuttingWheelActorVisual(visualizationContext, simulationWorld, context);
    }

    @Override
    public boolean isActive(final MovementContext context) {
        return context.contraption.entity instanceof BoreheadContraptionEntity;
    }
}
