package ipl.sable.dim;

import dev.ryanhcode.sable.api.entity.EntitySubLevelUtil;
import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import ipl.sable.mixin.IplEntityLevelInvoker;
import ipl.sable.mixin.IplEntityLifecycleAccessor;
import ipl.sable.mixin.IplServerEntityGetterInvoker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import java.util.*;

/**
 * Plot-resident retained entities share the plot's storage. Kinematic actors instead
 * belong to the physical world: their entity/collision registries must tick there,
 * while their Sable voxel body is forwarded to the mount's owning pipeline.
 * This distinction uses Sable's actor interface, never a mod ID or entity class name.
 */
public final class IplHostedEntityOwnership {
 private static final Set<Entity> TRANSFERRING=Collections.newSetFromMap(new IdentityHashMap<>());
 private static final Set<Entity> PENDING=Collections.newSetFromMap(new IdentityHashMap<>());
 private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("ipl-entity-ownership");
 private IplHostedEntityOwnership() {}
 public static ServerLevel owner(Entity entity, ServerLevel source) {
  if(TRANSFERRING.contains(entity) || entity instanceof Player || entity.isRemoved() || EntitySubLevelUtil.shouldKick(entity))return source;
  var hosting=IplDimAgnostic.getHostingContainerFor(source);
  if(hosting==null || !(hosting.getLevel() instanceof ServerLevel storage))return source;
  ServerLevel parent=IplWorldFrameContext.resolveParentForPlotBe(storage,entity.blockPosition());
  if(parent==null || (!IplDimAgnostic.isHostingLevel(source) && source!=parent))return source;
  return entity instanceof KinematicContraption ? parent : storage;
 }
 public static void moved(Entity entity) {
  if(entity.level() instanceof ServerLevel level && level.getServer().isSameThread())PENDING.add(entity);
 }
 public static void tick(MinecraftServer server) {
  var storage=SableSubLevelDimension.getSableSubLevelsOrNull(server);
  // Repair retained actors loaded from an earlier build too; they may never move.
  if(storage!=null)for(Entity entity:((IplServerEntityGetterInvoker)storage).ipl$entityGetter().getAll())
   if(entity instanceof KinematicContraption)PENDING.add(entity);
  if(server.getTickCount()%20==0) {
   IplParentPlotEntityTicking.reconcilePlots(server);
   for(ServerLevel level:server.getAllLevels())
    for(Entity entity:((IplServerEntityGetterInvoker)level).ipl$entityGetter().getAll())
     if(!(entity instanceof Player) && !EntitySubLevelUtil.shouldKick(entity))PENDING.add(entity);
  }
  var batch=new ArrayList<>(PENDING);PENDING.clear();
  for(Entity entity:batch) {
   if(entity.isRemoved() || !(entity.level() instanceof ServerLevel source) || source.getServer()!=server)continue;
   var getter=((IplServerEntityGetterInvoker)source).ipl$entityGetter();
   if(getter.get(entity.getUUID())!=entity)continue; // constructed but not inserted yet
   ServerLevel target=owner(entity,source);
   if(target==source)continue;
   transfer(entity,target,entity.position());
  }
 }
 /** Transfer a retained entity without remove()/NBT recreation. In particular,
  * auxiliary selection volumes must survive Entity.setPos's normal box rebuild. */
 public static boolean transfer(Entity entity,ServerLevel target,net.minecraft.world.phys.Vec3 position) {
  if(!(entity.level() instanceof ServerLevel source) || entity instanceof Player)return false;
  var oldPos=entity.position(); var oldBox=entity.getBoundingBox();
  var newBox=oldBox.move(position.subtract(oldPos));
  if(source==target) {
   entity.moveTo(position.x,position.y,position.z,entity.getYRot(),entity.getXRot());
   entity.setBoundingBox(newBox);
   IplParentPlotEntityTicking.update(entity,entity.getX(),entity.getZ());
   return true;
  }
  if(!TRANSFERRING.add(entity))throw new IllegalStateException("Recursive hosted entity transfer");
  boolean detached=false;
  try {
   ((IplEntityLifecycleAccessor)entity).ipl$levelCallback().onRemove(Entity.RemovalReason.CHANGED_DIMENSION);
   detached=true;
   ((IplEntityLevelInvoker)entity).ipl$invokeSetLevel(target);
   entity.moveTo(position.x,position.y,position.z,entity.getYRot(),entity.getXRot());
   entity.setBoundingBox(newBox);
   if(!target.addFreshEntity(entity)) {
    restore(entity,source,target,oldPos,oldBox);
    LOG.warn("Rejected hosted entity owner transfer {} -> {}: {}",source.dimension().location(),target.dimension().location(),entity.getUUID());
    return false;
   }
   // onAddedToLevel may rebuild a selection volume as an ordinary point entity.
   entity.setBoundingBox(newBox);
   IplParentPlotEntityTicking.update(entity,entity.getX(),entity.getZ());
   return true;
  } catch(RuntimeException | Error failure) {
   if(detached && ((IplServerEntityGetterInvoker)source).ipl$entityGetter().get(entity.getUUID())!=entity) {
    try {restore(entity,source,target,oldPos,oldBox);}
    catch(RuntimeException | Error rollback) {failure.addSuppressed(rollback);}
   }
   throw failure;
  } finally { TRANSFERRING.remove(entity); }
 }
 private static void restore(Entity entity,ServerLevel source,ServerLevel target,
     net.minecraft.world.phys.Vec3 position,net.minecraft.world.phys.AABB box) {
  // A join listener may throw after insertion. Undo that partial join before rollback.
  if(((IplServerEntityGetterInvoker)target).ipl$entityGetter().get(entity.getUUID())==entity)
   ((IplEntityLifecycleAccessor)entity).ipl$levelCallback().onRemove(Entity.RemovalReason.CHANGED_DIMENSION);
  ((IplEntityLevelInvoker)entity).ipl$invokeSetLevel(source);
  entity.moveTo(position.x,position.y,position.z,entity.getYRot(),entity.getXRot());
  entity.setBoundingBox(box);
  // Explicit migration/rollback bypasses only our owner reroute, not join validation.
  if(!source.addFreshEntity(entity))throw new IllegalStateException("Unable to restore hosted entity "+entity.getUUID());
  entity.setBoundingBox(box);
  IplParentPlotEntityTicking.update(entity,entity.getX(),entity.getZ());
 }
 public static void clear(){PENDING.clear();TRANSFERRING.clear();IplParentPlotEntityTicking.clear();}
}
