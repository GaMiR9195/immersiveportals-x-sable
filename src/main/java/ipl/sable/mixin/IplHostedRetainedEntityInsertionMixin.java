package ipl.sable.mixin;
import ipl.sable.dim.IplHostedEntityOwnership;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Resolve actual entity ownership before the manager posts a join event. */
@Mixin(ServerLevel.class)
public abstract class IplHostedRetainedEntityInsertionMixin {
 @Inject(method="addEntity",at=@At("HEAD"),cancellable=true,require=1)
 private void ipl$resolveRetainedOwner(Entity entity,CallbackInfoReturnable<Boolean> cir) {
  ServerLevel source=(ServerLevel)(Object)this;
  ServerLevel target=IplHostedEntityOwnership.owner(entity,source);
  if(target==source)return;
  var old=entity.level();
  ((IplEntityLevelInvoker)entity).ipl$invokeSetLevel(target);
  boolean added=false;
  try {added=target.addFreshEntity(entity);cir.setReturnValue(added);}
  finally {if(!added)((IplEntityLevelInvoker)entity).ipl$invokeSetLevel(old);}
 }
 @Inject(method="addEntity",at=@At("RETURN"),require=1)
 private void ipl$reconcileLoadedOwner(Entity entity,CallbackInfoReturnable<Boolean> cir) {
  if(cir.getReturnValueZ()) {
   IplHostedEntityOwnership.moved(entity);
   ipl.sable.dim.IplParentPlotEntityTicking.update(entity,entity.getX(),entity.getZ());
  }
 }
}
