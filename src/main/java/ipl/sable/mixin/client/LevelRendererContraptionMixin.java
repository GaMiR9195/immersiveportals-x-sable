package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import ipl.sable.render.IplContraptionRender;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Contraptions on straddling bodies draw only their native piece in their level's entity pass. */
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
}
