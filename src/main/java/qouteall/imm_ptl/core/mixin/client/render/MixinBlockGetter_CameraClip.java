package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.render.PortalCameraClip;

/**
 * Camera rays pass portals ({@link PortalCameraClip}). Priority above Sable's overwrite of
 * {@code clip}, so this applies to the method that runs.
 */
@Mixin(value = BlockGetter.class, priority = 1200)
public interface MixinBlockGetter_CameraClip {
    
    @Inject(method = "clip", at = @At("HEAD"), cancellable = true)
    private void ip_clipThroughPortals(ClipContext context, CallbackInfoReturnable<BlockHitResult> cir) {
        BlockHitResult result = PortalCameraClip.clip((BlockGetter) this, context);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }
}
