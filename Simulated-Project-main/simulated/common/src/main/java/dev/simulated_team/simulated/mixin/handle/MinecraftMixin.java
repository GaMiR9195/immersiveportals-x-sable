package dev.simulated_team.simulated.mixin.handle;

import dev.simulated_team.simulated.index.SimClickInteractions;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "startUseItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/InteractionResult;shouldSwing()Z"))
    private void makeHandleHandlerCountUsing(final CallbackInfo ci) {
        SimClickInteractions.HANDLE_HANDLER.actuallyUsedBlockCountdown = 4;
    }
}
