package ipl.sable.client;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.ChunkPos;
/** Authoritative plot identity/state, without loading ordinary world chunks. */
public final class IplHostedBlockEntityLiveness {
 private IplHostedBlockEntityLiveness() {}
 public static boolean isLive(BlockEntity be) {
  if (be.isRemoved() || be.getLevel() == null) return false;
  if (!IplDimAgnostic.isHostingLevel(be.getLevel())) return true;
  var container=SubLevelContainer.getContainer(be.getLevel());
  if(container==null)return false;
  var pos=be.getBlockPos(); var plot=container.getPlot(pos.getX()>>4,pos.getZ()>>4);
  if(plot==null||plot.getSubLevel().isRemoved())return false;
  var holder=plot.getChunkHolder(plot.toLocal(new ChunkPos(pos)));
  var chunk=holder==null?null:holder.getChunk();
  if(chunk==null||chunk.getBlockEntities().get(pos)!=be)return false;
  var state=chunk.getBlockState(pos);
  return state.hasBlockEntity() && be.getType().isValid(state);
 }
}
