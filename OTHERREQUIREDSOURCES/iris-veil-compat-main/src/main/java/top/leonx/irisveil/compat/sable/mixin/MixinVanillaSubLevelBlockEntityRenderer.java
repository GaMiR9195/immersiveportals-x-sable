package top.leonx.irisveil.compat.sable.mixin;

import dev.ryanhcode.sable.mixinhelpers.sublevel_render.vanilla.VanillaSubLevelBlockEntityRenderer;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.server.level.BlockDestructionProgress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.SortedSet;

@Mixin(value = VanillaSubLevelBlockEntityRenderer.class, remap = false)
public interface MixinVanillaSubLevelBlockEntityRenderer {
    @Invoker("<init>")
    static VanillaSubLevelBlockEntityRenderer irisveil$create(
            BlockEntityRenderDispatcher blockEntityRenderDispatcher,
            RenderBuffers renderBuffers,
            Long2ObjectMap<SortedSet<BlockDestructionProgress>> destructionProgress) {
        throw new AssertionError();
    }
}
