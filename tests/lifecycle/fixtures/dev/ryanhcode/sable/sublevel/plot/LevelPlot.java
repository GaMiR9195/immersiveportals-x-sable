package dev.ryanhcode.sable.sublevel.plot;
import java.util.*; import net.minecraft.world.level.chunk.*;
public class LevelPlot {
 private final dev.ryanhcode.sable.sublevel.SubLevel owner;
 public final List<dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor> actors=new ArrayList<>();
 public LevelPlot(dev.ryanhcode.sable.sublevel.SubLevel owner){this.owner=owner;}
 public dev.ryanhcode.sable.sublevel.SubLevel getSubLevel(){return owner;}
 public Iterable<dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor> getBlockEntityActors(){return actors;}
 public boolean contains(net.minecraft.world.level.ChunkPos pos){return chunks.containsKey(key(pos.x,pos.z));}
 private final Map<Long,PlotChunkHolder> chunks=new LinkedHashMap<>();
 private static long key(int x,int z){return (long)x<<32|(z&0xffffffffL);}
 public Collection<PlotChunkHolder> getLoadedChunks(){return chunks.values();}
 public net.minecraft.world.level.ChunkPos getChunkMin(){return new net.minecraft.world.level.ChunkPos(0,0);}
 public LevelChunk getChunk(int x,int z){var h=chunks.get(key(x,z));return h==null?null:h.getChunk();}
 public LevelChunkSection section(int x,int y,int z){var c=getChunk(x,z);return c==null?null:c.section(y);}
 public void put(int x,int y,int z){var h=chunks.computeIfAbsent(key(x>>4,z>>4), k->new PlotChunkHolder(new LevelChunk(x>>4,z>>4)));h.getChunk().section(y>>4).set(x,y,z);}
}
