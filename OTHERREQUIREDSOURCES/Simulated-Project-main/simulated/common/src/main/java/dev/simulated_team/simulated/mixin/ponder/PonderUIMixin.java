package dev.simulated_team.simulated.mixin.ponder;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.simulated_team.simulated.mixin_interface.ponder.PonderSceneExtension;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(PonderUI.class)
public class PonderUIMixin {
    @Shadow private List<PonderScene> scenes;

    @ModifyConstant(method = "renderScene", constant = @Constant(intValue = 0x66_000000, ordinal = 0))
    private int customShadowFade(final int constant, final GuiGraphics graphics, final int mouseX, final int mouseY, final int i, final float partialTicks) {
        final int alpha = (int)((constant >> 24) * ((PonderSceneExtension) this.scenes.get(i)).simulated$getBasePlateAnimationTimer(partialTicks));
        return (alpha << 24) | (constant & 0x00_FFFFFF);
    }

    @Inject(method = "renderScene", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V", ordinal = 1, shift = At.Shift.AFTER))
    private void shadowTranslate(final GuiGraphics graphics, final int mouseX, final int mouseY, final int i, final float partialTicks, final CallbackInfo ci, @Local final PoseStack ms) {
        final Vec3 offset = ((PonderSceneExtension)(this.scenes.get(i))).simulated$getShadowOffset(partialTicks);
        ms.translate(offset.x, offset.y, offset.z);
    }
}
