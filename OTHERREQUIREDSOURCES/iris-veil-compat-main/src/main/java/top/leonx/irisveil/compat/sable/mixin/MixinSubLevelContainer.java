package top.leonx.irisveil.compat.sable.mixin;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = SubLevelContainer.class, remap = false)
public interface MixinSubLevelContainer {
    @Invoker("getContainer")
    static ClientSubLevelContainer irisveil$getClientContainer(ClientLevel level) {
        throw new AssertionError();
    }
}
