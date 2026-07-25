package foundry.veil.forge.mixin.compat.sodium;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SortedRenderLists.class)
public interface SortedRenderListsAccessor {

    @Invoker("<init>")
    static SortedRenderLists init(ObjectArrayList<ChunkRenderList> lists) {
        throw new AssertionError();
    }
}
