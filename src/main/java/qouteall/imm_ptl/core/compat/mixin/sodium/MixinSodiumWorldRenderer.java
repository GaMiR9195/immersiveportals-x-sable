package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import net.minecraft.client.Options;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.render.FrustumCuller;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public class MixinSodiumWorldRenderer {
    @Shadow
    private int renderDistance;
    
    @Inject(
        method = "setupTerrain",
        at = @At("HEAD")
    )
    private void onUpdateChunks(
        Camera camera, Viewport viewport, boolean spectator, boolean updateChunksImmediately, CallbackInfo ci
    ) {
        SodiumInterface.frustumCuller = new FrustumCuller();
        Vec3 cameraPos = camera.getPosition();
        SodiumInterface.frustumCuller.update(cameraPos.x, cameraPos.y, cameraPos.z);
    }
    
    /**
     * A pass of another world view must not rebuild the renderer: its section manager holds that
     * view's swapped-in context. Renderers of other levels follow a render distance change before
     * the frame ({@code ClientWorldLoader}).
     */
    @Redirect(
        method = "setupTerrain",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getEffectiveRenderDistance()I")
    )
    private int redirectRenderDistanceCheck(Options options) {
        return WorldRenderInfo.isRendering() ? renderDistance : options.getEffectiveRenderDistance();
    }
}
