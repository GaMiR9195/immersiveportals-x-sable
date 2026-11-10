package dev.ryanhcode.sable.api.sublevel;
import java.util.*; import net.minecraft.server.level.ServerLevel; import net.minecraft.world.level.ChunkPos;
import dev.ryanhcode.sable.sublevel.*; import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
public final class ServerSubLevelContainer {
 private final ServerLevel level;public final List<ServerSubLevel> members=new ArrayList<>();
 public ServerSubLevelContainer(ServerLevel level){this.level=level;} public ServerLevel getLevel(){return level;}
 public List<ServerSubLevel> getAllSubLevels(){return members;}
 public ServerSubLevel getSubLevel(UUID id){return members.stream().filter(m->!m.isRemoved()&&m.getUniqueId().equals(id)).findFirst().orElse(null);}
 public LevelPlot getPlot(ChunkPos pos){for(var m:members)if(!m.isRemoved()&&m.getPlot().contains(pos))return m.getPlot();return null;}
}
