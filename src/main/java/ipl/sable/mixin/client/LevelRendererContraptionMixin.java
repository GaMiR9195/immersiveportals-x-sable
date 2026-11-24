package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import ipl.sable.render.IplContraptionRender;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Contraptions on bodies: culled by everything they cover, not by their anchor, and on straddling
 * bodies drawn only with their native piece in their level's entity pass.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererContraptionMixin {

    @WrapOperation(
        method = "renderLevel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderEntity(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V")
    )
    private void ipl$contraptionPiece(LevelRenderer renderer, Entity entity, double camX, double camY, double camZ,
                                      float partialTick, PoseStack poseStack, MultiBufferSource buffers,
                                      Operation<Void> original) {
        IplContraptionRender.drawInOwnPass(entity, buffers,
            () -> original.call(renderer, entity, camX, camY, camZ, partialTick, poseStack, buffers));
    }

    @WrapOperation(
        method = "renderLevel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z")
    )
    private boolean ipl$contraptionInView(EntityRenderDispatcher dispatcher, Entity entity, Frustum frustum,
                                          double camX, double camY, double camZ, Operation<Boolean> original) {
        return IplContraptionRender.inView(entity, frustum, original.call(dispatcher, entity, frustum, camX, camY, camZ));
    }
}
