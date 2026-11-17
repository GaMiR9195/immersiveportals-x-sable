package qouteall.imm_ptl.core.compat.mixin.iris;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.uniforms.BiomeUniforms;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisViewSpace;

/** Biome uniforms are read where the player appears in the view being rendered. */
@Mixin(value = BiomeUniforms.class, remap = false)
public class MixinIrisBiomeUniforms {
    @WrapOperation(
        method = "*",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;level()Lnet/minecraft/world/level/Level;")
    )
    private static Level ip_level(LocalPlayer player, Operation<Level> original) {
        return IrisViewSpace.level(original.call(player));
    }

    @WrapOperation(
        method = "*",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;blockPosition()Lnet/minecraft/core/BlockPos;")
    )
    private static BlockPos ip_blockPosition(LocalPlayer player, Operation<BlockPos> original) {
        return IrisViewSpace.blockPosition(player, original.call(player));
    }
}
