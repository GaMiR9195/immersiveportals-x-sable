package ipl.sable.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.dim.IplOwnedSubLevels;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import java.util.List;
/** Keep Sable's storage, stepping, tracking and shutdown loops owner-local. */
@Mixin(targets = {"dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager", "dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem", "dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem", "dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap"}, remap = false)
public abstract class IplOwnedSubLevelConsumersMixin {
    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/api/sublevel/ServerSubLevelContainer;getAllSubLevels()Ljava/util/List;"), require = 0)
    private List<?> ipl$serverOwned(ServerSubLevelContainer container, Operation<List<?>> original) {
        return IplOwnedSubLevels.owned(container);
    }
    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;getAllSubLevels()Ljava/util/List;"), require = 0)
    private List<?> ipl$baseOwned(@org.spongepowered.asm.mixin.injection.Coerce SubLevelContainer container, Operation<List<?>> original) {
        return IplOwnedSubLevels.owned(container);
    }
}
