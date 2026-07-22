package top.leonx.irisveil.compat.sable.mixin;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = SubLevelRenderDispatcher.class, remap = false)
public interface MixinSubLevelRenderDispatcher {
    @Invoker("get")
    static SubLevelRenderDispatcher irisveil$get() {
        throw new AssertionError();
    }

    @Invoker("renderBlockEntities")
    void irisveil$renderBlockEntities(
        Iterable<ClientSubLevel> subLevels,
        SubLevelRenderDispatcher.BlockEntityRenderer blockEntityRenderer,
        double cameraX,
        double cameraY,
        double cameraZ,
        float partialTick);
}
