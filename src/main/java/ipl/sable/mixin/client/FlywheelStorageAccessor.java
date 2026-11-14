package ipl.sable.mixin.client;

import dev.engine_room.flywheel.api.visual.Visual;
import dev.engine_room.flywheel.impl.visualization.storage.Storage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Flywheel's own record of which objects have a visual. {@code BlockEntityStorage.visualAtPos}
 * is not that record: Sable's {@code BlockEntityStorageMixin} overrides {@code remove} and calls
 * {@code Storage.remove} directly, so {@code posLookup} keeps every removed visual.
 */
@Mixin(value = Storage.class, remap = false)
public interface FlywheelStorageAccessor {

    @Accessor("visuals")
    Map<Object, Visual> ipl$visuals();
}
