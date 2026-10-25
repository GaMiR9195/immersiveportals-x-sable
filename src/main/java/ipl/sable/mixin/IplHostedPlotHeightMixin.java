package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ipl.sable.dim.IplHostedPlotHeight;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Parent block access already resolves the owning storage chunk. Use its profile for
 * the preceding position gate too, without spoofing scalar world heights. */
@Mixin(Level.class)
public abstract class IplHostedPlotHeightMixin {
    @WrapOperation(method = "*", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/world/level/Level;isOutsideBuildHeight(Lnet/minecraft/core/BlockPos;)Z"), require = 1)
    private boolean ipl$plotProfile(Level level, BlockPos pos, Operation<Boolean> original) {
        var profile = IplHostedPlotHeight.profile(level, pos);
        return profile == level ? original.call(level, pos) : profile.isOutsideBuildHeight(pos);
    }
}
