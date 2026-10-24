package ipl.sable.mixin.client;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import ipl.sable.client.IplClientVisualViewContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(BlockEntity.class)
public abstract class IplFlywheelBeLifecycleMixin {
    @WrapMethod(method = "setRemoved")
    private void ipl$visualView(Operation<Void> original) {
        var be = (BlockEntity)(Object)this;
        var frame = IplClientVisualViewContext.enter(be.getLevel(),be.getBlockPos());
        try { original.call(); } finally { IplClientVisualViewContext.exit(frame); }
    }
}
