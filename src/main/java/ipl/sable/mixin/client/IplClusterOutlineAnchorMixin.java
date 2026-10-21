package ipl.sable.mixin.client;

import ipl.sable.duck.IplClusterOutlineAnchor;
import net.createmod.catnip.outliner.BlockClusterOutline;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = BlockClusterOutline.class, remap = false)
public abstract class IplClusterOutlineAnchorMixin implements IplClusterOutlineAnchor {
    @Unique private BlockPos ipl$anchor;
    @Inject(method = "<init>", at = @At("TAIL"), require = 1)
    private void ipl$captureAnchor(Iterable<BlockPos> positions, CallbackInfo ci) {
        for (BlockPos pos : positions) { ipl$anchor = pos.immutable(); break; }
    }
    @Override public @Nullable BlockPos ipl$getOutlineAnchor() { return ipl$anchor; }
}
