package ipl.sable.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import ipl.sable.dim.IplReadViewScope;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
@Pseudo
@Mixin(targets = "com.misterblusky9.pym.internal.interaction.ScaleAwareBlockClip", remap = false)
public abstract class IplScaledClipReadViewMixin {
    @WrapMethod(method = "clip")
    private static BlockHitResult ipl$readView(BlockGetter level, ClipContext context, Operation<BlockHitResult> original) {
        int previous = IplReadViewScope.enter();
        try { return original.call(level, context); } finally { IplReadViewScope.exit(previous); }
    }
}
