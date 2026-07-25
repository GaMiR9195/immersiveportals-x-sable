package foundry.veil.fabric.mixin.client;

import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.fabric.FabricRenderTypeStageHandler;
import foundry.veil.fabric.event.FabricFreeNativeResourcesEvent;
import foundry.veil.fabric.event.FabricVeilRegisterFixedBuffersEvent;
import foundry.veil.fabric.event.FabricVeilRendererAvailableEvent;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;resizeDisplay()V", shift = At.Shift.BEFORE))
    public void init(CallbackInfo ci) {
        VeilRenderSystem.init();
        FabricVeilRendererAvailableEvent.EVENT.invoker().onVeilRendererAvailable(VeilRenderSystem.renderer());
        FabricVeilRegisterFixedBuffersEvent.EVENT.invoker().onRegisterFixedBuffers(FabricRenderTypeStageHandler::register);
    }

    @Inject(method = "close", at = @At(value = "INVOKE", target = "Lnet/minecraft/Util;shutdownExecutors()V", shift = At.Shift.BEFORE))
    public void close(CallbackInfo ci) {
        FabricFreeNativeResourcesEvent.EVENT.invoker().onFree();
    }
}
