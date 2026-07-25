package top.leonx.irisveil.mixin.iris;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import top.leonx.irisveil.accessors.GameRendererAccessor;

@Mixin(GameRenderer.class)
public interface MixinGameRendererAccessor extends GameRendererAccessor {
    @Invoker("getFov")
    @Override
    double irisveil$invokeGetFov(Camera camera, float partialTicks, boolean useFovSetting);
}
