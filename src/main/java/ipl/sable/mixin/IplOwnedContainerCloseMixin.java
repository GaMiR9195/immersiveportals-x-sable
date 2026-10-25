package ipl.sable.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import ipl.sable.dim.IplOwnedSubLevels;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import java.util.List;
@Mixin(value=ServerSubLevelContainer.class, remap=false)
public abstract class IplOwnedContainerCloseMixin {
 @WrapOperation(method="close", at=@At(value="INVOKE", target="Ldev/ryanhcode/sable/api/sublevel/ServerSubLevelContainer;getAllSubLevels()Ljava/util/List;"), require=1)
 private List<?> ipl$closeOwned(ServerSubLevelContainer container, Operation<List<?>> original) {
  return IplOwnedSubLevels.owned(container);
 }
}
