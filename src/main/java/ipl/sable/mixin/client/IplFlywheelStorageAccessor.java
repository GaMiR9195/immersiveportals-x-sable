package ipl.sable.mixin.client;
import dev.engine_room.flywheel.api.visual.Visual;
import dev.engine_room.flywheel.impl.visualization.storage.Storage;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
@Pseudo
@Mixin(value = Storage.class, remap = false)
public interface IplFlywheelStorageAccessor {
    @Accessor("visuals") Map<Object, Visual> ipl$visuals();
}
