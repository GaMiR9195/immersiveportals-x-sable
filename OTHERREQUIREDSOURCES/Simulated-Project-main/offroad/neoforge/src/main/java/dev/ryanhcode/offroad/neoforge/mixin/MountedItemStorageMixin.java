package dev.ryanhcode.offroad.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.api.contraption.storage.item.MountedItemStorage;
import com.simibubi.create.content.contraptions.Contraption;
import dev.ryanhcode.offroad.content.contraptions.borehead_contraption.BoreheadBearingContraption;
import dev.ryanhcode.offroad.neoforge.mixin_helpers.WrappedWrappedMountedItemStorage;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.spongepowered.asm.mixin.Mixin;

import java.lang.ref.WeakReference;

@Mixin(MountedItemStorage.class)
public class MountedItemStorageMixin {

    @WrapMethod(method = "getHandlerForMenu")
    public IItemHandlerModifiable offroad$wrapHandler(final StructureTemplate.StructureBlockInfo info, final Contraption contraption, final Operation<IItemHandlerModifiable> original) {
        final IItemHandlerModifiable originalCall = original.call(info, contraption);
        if (contraption instanceof BoreheadBearingContraption && originalCall != null) {
            return new WrappedWrappedMountedItemStorage(new WeakReference<>(contraption), originalCall);
        }

        return originalCall;
    }

}
