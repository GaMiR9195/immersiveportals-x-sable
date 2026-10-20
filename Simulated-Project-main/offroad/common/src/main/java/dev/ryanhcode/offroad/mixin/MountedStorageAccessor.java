package dev.ryanhcode.offroad.mixin;

import com.simibubi.create.api.contraption.storage.item.MountedItemStorage;
import com.simibubi.create.content.contraptions.MountedStorageManager;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(MountedStorageManager.class)
public interface MountedStorageAccessor {

    @Accessor
    Map<BlockPos, MountedItemStorage> getItemsBuilder();

}
