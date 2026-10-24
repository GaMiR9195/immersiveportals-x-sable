package ipl.sable.mixin;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.dim.IplHostedSpatialQueries;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(value = SubLevelContainer.class, remap = false)
public abstract class IplContainerSpatialReadMixin {
    @Shadow public abstract Level getLevel();
    @ModifyReturnValue(method = "queryIntersecting", at = @At("RETURN"), require = 1)
    private Iterable<SubLevel> ipl$queryView(Iterable<SubLevel> original, BoundingBox3dc bounds) {
        return IplHostedSpatialQueries.include(getLevel(),bounds,original);
    }
}
