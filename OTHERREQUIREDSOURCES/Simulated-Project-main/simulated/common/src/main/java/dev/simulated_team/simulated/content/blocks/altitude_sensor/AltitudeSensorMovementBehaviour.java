package dev.simulated_team.simulated.content.blocks.altitude_sensor;

import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.render.ContraptionMatrices;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.util.Tuple;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;

public class AltitudeSensorMovementBehaviour implements MovementBehaviour {

    @Override
    public boolean disableBlockEntityRendering() {
        return true;
    }

    @Override
    public void tick(final MovementContext context) {
        MovementBehaviour.super.tick(context);

        // temporaryData <- (previousVisualHeight, visualHeight)
        final float yPos = (float) Sable.HELPER.projectOutOfSubLevel(context.world, JOMLConversion.toJOML(context.position)).y;
        if (context.temporaryData instanceof final Tuple<?, ?> heights) {
            context.temporaryData = new Tuple<>(heights.getB(), yPos);
        } else {
            context.temporaryData = new Tuple<>(yPos, yPos);
        }
    }

    @Override
    public void renderInContraption(final MovementContext context, final VirtualRenderWorld renderWorld, final ContraptionMatrices matrices, final MultiBufferSource buffer) {
        final float lowSignal = context.blockEntityData.getFloat("low_signal");
        final float highSignal = context.blockEntityData.getFloat("high_signal");

        final float visualHeight;
        if (context.temporaryData instanceof final Tuple<?, ?> heights) {
            visualHeight = ((float) heights.getA()) * (1 - AnimationTickHolder.getPartialTicks()) + (float) heights.getB() * AnimationTickHolder.getPartialTicks();
        } else {
            final Vector3d pos = context.position != null ? JOMLConversion.toJOML(context.position) : new Vector3d();
            visualHeight = (float) Sable.HELPER.projectOutOfSubLevel(context.world, pos).y;
        }

        final Level level = context.contraption.entity.level();
        final float y = (float) Mth.map(context.position.y, level.getMinBuildHeight(), level.getMaxBuildHeight(), 0.0f, 1.0f);
        final float value = Mth.clampedMap(y, 0.0f, 1.0f, lowSignal, highSignal);

        AltitudeSensorRenderer.render(context.state, 1000, value, visualHeight, matrices.getViewProjection(), matrices.getModel(), matrices.getWorld(), buffer, LevelRenderer.getLightColor(renderWorld, context.localPos));
    }
}
