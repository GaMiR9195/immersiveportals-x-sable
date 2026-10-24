package ipl.sable.mixin.client;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import ipl.sable.client.IplClientVisualViewContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
@Mixin(LevelRenderer.class)
public abstract class IplFlywheelDirtyLifecycleMixin {
    @Shadow private ClientLevel level;
    @WrapMethod(method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V")
    private void ipl$visualView(BlockPos pos,BlockState oldState,BlockState newState,Operation<Void> original) {
        var frame = IplClientVisualViewContext.enter(level,pos);
        try { original.call(pos,oldState,newState); } finally { IplClientVisualViewContext.exit(frame); }
    }
}
