package net.minecraft.server.level;
import java.util.*; import net.minecraft.server.MinecraftServer; import net.minecraft.core.BlockPos; import net.minecraft.world.level.block.entity.BlockEntity;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
public class ServerLevel {public final String id; private final MinecraftServer server;
 private static long nextScene;public long scene=++nextScene;public ServerSubLevelContainer container=new ServerSubLevelContainer(this);
 public final Map<BlockPos,BlockEntity> blockEntities=new HashMap<>();public int loadedProbes,blockEntityProbes;
 public ServerLevel(MinecraftServer s,String id){this.server=s;this.id=id;s.levels.put(id,this);} public MinecraftServer getServer(){return server;}
 public boolean isLoaded(BlockPos p){loadedProbes++;return blockEntities.containsKey(p);}public BlockEntity getBlockEntity(BlockPos p){blockEntityProbes++;return blockEntities.get(p);}
 public record Dimension(String location){} public Dimension dimension(){return new Dimension(id);}}
