package ipl.sable.mixin;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.List;
/** Physical plot ownership, never a world membership query. */
@Mixin(SubLevelContainer.class)
public interface IplOwnedSubLevelsAccessor {
    @Accessor("allSubLevels") List<SubLevel> ipl$ownedSubLevels();
}
