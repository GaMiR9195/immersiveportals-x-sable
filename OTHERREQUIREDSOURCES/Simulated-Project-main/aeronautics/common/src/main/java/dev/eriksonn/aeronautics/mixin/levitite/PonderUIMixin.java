package dev.eriksonn.aeronautics.mixin.levitite;

import dev.eriksonn.aeronautics.content.blocks.levitite.LevititeShaderManager;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PonderUI.class)
public class PonderUIMixin {
    @Inject(method = "renderScene",at = @At("HEAD"))
    protected void renderScene(GuiGraphics graphics, int mouseX, int mouseY, int i, float partialTicks, CallbackInfo ci) {
        LevititeShaderManager.disableShader();
    }
}
