package qouteall.imm_ptl.core.mixin.client.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.render.NameTagFootprints;

@Mixin(EntityRenderer.class)
public class MixinEntityRenderer_NameTag {
    @WrapOperation(
        method = "renderNameTag",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I"
        )
    )
    private int ip_recordNameTag(
        Font font, Component text, float x, float y, int color, boolean dropShadow, Matrix4f pose,
        MultiBufferSource buffers, Font.DisplayMode mode, int backgroundColor, int light,
        Operation<Integer> original
    ) {
        if (backgroundColor != 0) {
            NameTagFootprints.record(pose, x, y, font.width(text));
        }
        return original.call(font, text, x, y, color, dropShadow, pose, buffers, mode, backgroundColor, light);
    }
}
