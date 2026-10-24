package ipl.sable.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import ipl.sable.dim.IplReadViewScope;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
/** Picking is a read view, not an ownership enumeration. Also covers nested local clips. */
@Mixin(BlockGetter.class)
public interface IplReadViewClipMixin {
    @WrapMethod(method = "clip")
    private BlockHitResult ipl$readView(ClipContext context, Operation<BlockHitResult> original) {
        int previous = IplReadViewScope.enter();
        try { return original.call(context); } finally { IplReadViewScope.exit(previous); }
    }
}
