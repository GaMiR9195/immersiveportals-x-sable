package dev.simulated_team.simulated.neoforge.mixin.diagram;

import dev.engine_room.flywheel.impl.visualization.VisualManagerImpl;
import dev.engine_room.flywheel.impl.visualization.storage.Storage;
import dev.ryanhcode.sable.neoforge.mixinhelper.compatibility.flywheel.SubLevelEmbedding;
import dev.ryanhcode.sable.neoforge.mixinterface.compatibility.flywheel.BlockEntityStorageExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.simulated_team.simulated.mixin_interface.diagram.VisualManagerExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(VisualManagerImpl.class)
public abstract class VisualManagerImplMixin<T, S extends Storage<T>> implements VisualManagerExtension {

    @Shadow public abstract S getStorage();

    @Override
    public SubLevelEmbedding sable$getBEEmbeddingInfo(final ClientSubLevel subLevel) {
        return ((BlockEntityStorageExtension) this.getStorage()).sable$getEmbeddingInfo(subLevel);
    }
}
