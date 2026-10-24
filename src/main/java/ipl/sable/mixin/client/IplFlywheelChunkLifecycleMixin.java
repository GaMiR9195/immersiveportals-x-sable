package ipl.sable.mixin.client;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import ipl.sable.client.IplClientVisualViewContext;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
/** Includes Flywheel's injected chunk callback without depending on its generated method name. */
@Mixin(LevelChunk.class)
public abstract class IplFlywheelChunkLifecycleMixin {
    @WrapMethod(method = "setBlockEntity")
    private void ipl$visualView(BlockEntity be,Operation<Void> original) {
        var frame = IplClientVisualViewContext.enter(((LevelChunk)(Object)this).getLevel(),be.getBlockPos());
        try { original.call(be); } finally { IplClientVisualViewContext.exit(frame); }
    }
}
