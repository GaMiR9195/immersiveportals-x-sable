package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import ipl.sable.dim.IplProcessingPass;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * A server container's tick (its own sub-level ticks, removals and observer systems — the
 * physics system tick that addons hook for per-world work) is that container's processing
 * pass. See {@link IplProcessingPass}: inside it, enumeration is owner-local, so a ship that
 * a parent world merely displays is processed once, by the container that owns it.
 */
@Mixin(value = SubLevelContainer.class, remap = false)
public abstract class IplContainerProcessingPassMixin {

    @Shadow public abstract Level getLevel();

    @WrapMethod(method = "tick", remap = false)
    private void ipl$ownerLocalPass(Operation<Void> original) {
        if (!(this.getLevel() instanceof ServerLevel)) {
            original.call();
            return;
        }
        IplProcessingPass.run((SubLevelContainer) (Object) this, original::call);
    }
}
