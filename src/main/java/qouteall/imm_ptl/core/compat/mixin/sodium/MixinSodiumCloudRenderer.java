package qouteall.imm_ptl.core.compat.mixin.sodium;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.caffeinemc.mods.sodium.client.render.immediate.CloudRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sodium_compatibility.CloudGeometryCache;

/** Keeps the cloud meshes of the views of a frame instead of rebuilding one per view. */
@Mixin(value = CloudRenderer.class, remap = false)
public abstract class MixinSodiumCloudRenderer {
    @Shadow
    private CloudRenderer.CloudGeometry builtGeometry;
    
    @Unique
    private final CloudGeometryCache ip_geometryCache = new CloudGeometryCache();
    
    @Inject(
        method = "render",
        at = @At(
            value = "FIELD",
            target = "Lnet/caffeinemc/mods/sodium/client/render/immediate/CloudRenderer;builtGeometry:Lnet/caffeinemc/mods/sodium/client/render/immediate/CloudRenderer$CloudGeometry;",
            opcode = Opcodes.GETFIELD,
            ordinal = 0
        )
    )
    private void ip_selectCachedGeometry(
        Camera camera, ClientLevel level, Matrix4f projectionMatrix, PoseStack poseStack,
        float ticks, float tickDelta, CallbackInfo ci,
        @Local CloudRenderer.CloudGeometryParameters parameters
    ) {
        builtGeometry = ip_geometryCache.select(builtGeometry, parameters);
    }
    
    @Inject(method = "destroy", at = @At("HEAD"))
    private void ip_onDestroy(CallbackInfo ci) {
        ip_geometryCache.clear();
    }
}
