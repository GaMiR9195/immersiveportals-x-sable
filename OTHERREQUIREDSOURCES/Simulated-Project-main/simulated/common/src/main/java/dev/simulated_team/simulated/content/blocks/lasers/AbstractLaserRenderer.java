package dev.simulated_team.simulated.content.blocks.lasers;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.foundation.blockEntity.renderer.SmartBlockEntityRenderer;
import dev.engine_room.flywheel.lib.transform.TransformStack;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.util.SableDistUtil;
import dev.simulated_team.simulated.index.SimRenderTypes;
import net.createmod.catnip.data.Couple;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;

public abstract class AbstractLaserRenderer<T extends AbstractLaserBlockEntity> extends SmartBlockEntityRenderer<T> {
    public AbstractLaserRenderer(final BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(final T blockEntity, final float partialTicks, final PoseStack pose, final MultiBufferSource buffer, final int light, final int overlay) {
        super.renderSafe(blockEntity, partialTicks, pose, buffer, light, overlay);

        final LaserBehaviour laser = blockEntity.getAllBehaviours().stream().filter(behaviour -> behaviour instanceof LaserBehaviour).map(behaviour -> (LaserBehaviour) behaviour).findFirst().orElse(null);

        if (laser != null && laser.shouldCast()) {
            final Vector4f colors = this.getColors(blockEntity, partialTicks);

            if (colors.w > 0) { // alpha > 0
                pose.pushPose();

                this.transformPose(blockEntity, laser, pose);
                final float distance = this.getLaserLength(laser);
                this.createLaser(colors, pose, buffer, laser.getRange(), distance );

                pose.popPose();
            }
        }
    }

    public abstract Vector4f getColors(T blockEntity, float partialTicks);

    public float getLaserLength(final LaserBehaviour laser) {
        float laserRange = laser.getRange();

        final HitResult hr = this.getRenderedHitResult(laser);
        final Couple<Vec3> positions = laser.getLaserPositions().get();
        if (hr != null && !hr.getType().equals(HitResult.Type.MISS)) {
            Vec3 hitPos = hr.getLocation();
            if (laser.getVirtualHitPos() != Vec3.ZERO) {
                hitPos = laser.getVirtualHitPos();
            }

            laserRange = (float) Math.sqrt(Sable.HELPER.distanceSquaredWithSubLevels(SableDistUtil.getClientLevel(), positions.getFirst(), hitPos)) - 0.1f;
        } else if (laser.getVirtualHitPos() != Vec3.ZERO) {
            final Vec3 hitPos = laser.getVirtualHitPos();

            laserRange = (float) Math.sqrt(Sable.HELPER.distanceSquaredWithSubLevels(SableDistUtil.getClientLevel(), positions.getFirst(), hitPos)) - 0.1f;
        }

        return laserRange;
    }

    public abstract float getLaserScale(final LaserBehaviour laser);

    public HitResult getRenderedHitResult(final LaserBehaviour laser) {
        return laser.getClosestHitResult();
    }

    protected void transformPose(final T blockEntity, final LaserBehaviour laser, final PoseStack pose) {
        final Direction facing = blockEntity.getDirection();

        pose.translate(0.5, 0.5, 0.5);

        TransformStack.of(pose)
                .rotate(facing.getRotation())
                .rotateXDegrees(-90)
                .translate(0, 0, 0.5 - 0.0625);

        final float scale = this.getLaserScale(laser);
        pose.scale(scale, scale, 1);

        pose.translate(-0.5, -0.5, 0.0);
    }

    protected void createLaser(final Vector4f color, final PoseStack pose, final MultiBufferSource buffer, final float maxLength, final float length) {
        final VertexConsumer builder;
        if (buffer instanceof final SuperRenderTypeBuffer superRenderTypeBuffer) {
            builder = superRenderTypeBuffer.getLateBuffer(SimRenderTypes.laser());
        } else {
            builder = buffer.getBuffer(SimRenderTypes.laser());
        }

        final float lengthFrac = length / maxLength;
        final float offset = lengthFrac / 10;
        final float endU = 1f + 1f / length; // frag shader uses v > 1 to taper off

        final float red = color.x();
        final float blue = color.y();
        final float green = color.z();
        final float alpha = color.w();
        final float endAlpha = alpha * (1 - lengthFrac);
        pose.pushPose();
        final Quaternionf rotationQuat = Axis.ZN.rotationDegrees(90);

        for (int i = 0; i < 4; i++) {
            final Matrix4f matrix = pose.last().pose();

            builder.addVertex(matrix, 0, 0f, 0).setColor(red, green, blue, alpha).setUv(0, endU).setLight(LightTexture.FULL_BRIGHT).setOverlay(OverlayTexture.NO_OVERLAY).setNormal(0.0f, 1.0f, 0.0f);
            builder.addVertex(matrix, 1, 0f, 0).setColor(red, green, blue, alpha).setUv(0, endU).setLight(LightTexture.FULL_BRIGHT).setOverlay(OverlayTexture.NO_OVERLAY).setNormal(0.0f, 1.0f, 0.0f);
            // offset makes the end of the laser spread out, helping reduce z-fighting
            builder.addVertex(matrix, 1 + offset, -offset, length + 0.5f).setColor(red, green, blue, endAlpha).setUv(endU, endU).setLight(LightTexture.FULL_BRIGHT).setOverlay(OverlayTexture.NO_OVERLAY).setNormal(0.0f, 1.0f, 0.0f);
            builder.addVertex(matrix, -offset, -offset, length + 0.5f).setColor(red, green, blue, endAlpha).setUv(endU, endU).setLight(LightTexture.FULL_BRIGHT).setOverlay(OverlayTexture.NO_OVERLAY).setNormal(0.0f, 1.0f, 0.0f);

            pose.translate(0.5, 0.5, 0.5);
            pose.mulPose(rotationQuat);
            pose.translate(-0.5, -0.5, -0.5);
        }
        pose.popPose();
    }

    @Override
    public boolean shouldRenderOffScreen(final @NotNull T blockEntity) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 256;
    }
}
