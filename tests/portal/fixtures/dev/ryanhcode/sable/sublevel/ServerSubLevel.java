package dev.ryanhcode.sable.sublevel;
import java.util.*;import dev.ryanhcode.sable.companion.math.*; import net.minecraft.core.BlockPos;
public class ServerSubLevel {
 private final UUID id=UUID.randomUUID();public final Pose3d current=new Pose3d(),previous=new Pose3d();
 public final List<BlockPos> blocks=new ArrayList<>(List.of(new BlockPos(0,0,0)));
 public ServerSubLevel(){current.rotationPoint().set(.5,.5,.5);previous.set(current);}
 public UUID getUniqueId(){return id;}public Pose3d lastPose(){return previous;}public Pose3d logicalPose(){return current;}
 public Plot getPlot(){return new Plot();}
 public class Plot {public Box getBoundingBox(){return new Box();}}
 public class Box {public int minX(){return blocks.stream().mapToInt(BlockPos::getX).min().orElse(0);}public int maxX(){return blocks.stream().mapToInt(BlockPos::getX).max().orElse(0);}
 public int minY(){return blocks.stream().mapToInt(BlockPos::getY).min().orElse(0);}public int maxY(){return blocks.stream().mapToInt(BlockPos::getY).max().orElse(0);}
 public int minZ(){return blocks.stream().mapToInt(BlockPos::getZ).min().orElse(0);}public int maxZ(){return blocks.stream().mapToInt(BlockPos::getZ).max().orElse(0);}}
}
