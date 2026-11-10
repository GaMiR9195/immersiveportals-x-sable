package dev.ryanhcode.sable.sublevel;
import java.util.*; import dev.ryanhcode.sable.companion.math.*; import dev.ryanhcode.sable.sublevel.plot.*; import net.minecraft.server.level.ServerLevel;
public class ServerSubLevel extends SubLevel {
 private static int nextRuntime; private final int runtime=++nextRuntime;
 private final UUID id; private ServerLevel level; private final LevelPlot plot=new LevelPlot(this); private final Pose3d pose=new Pose3d();
 public boolean removed;
 public ServerSubLevel(UUID id,ServerLevel l){this.id=id;level=l;l.container.members.add(this);}
 public UUID getUniqueId(){return id;} public ServerLevel getLevel(){return level;}
 public int getRuntimeId(){return runtime;}
 public void moveTo(ServerLevel next){level.container.members.remove(this);level=next;next.container.members.add(this);}
 public LevelPlot getPlot(){return plot;} public Pose3d logicalPose(){return pose;} public Pose3d lastPose(){return pose;} public boolean isRemoved(){return removed;}
}
