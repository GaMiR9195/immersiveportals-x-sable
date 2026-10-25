package ipl.sable.dim;
public class IplDimAgnostic {public static Container hosting;public static Container getHostingContainerFor(net.minecraft.world.level.Level l){return hosting;}
 public static boolean isHostingLevel(net.minecraft.world.level.Level l){return l instanceof net.minecraft.server.level.ServerLevel s&&s.storage;}
 public record Container(net.minecraft.world.level.Level level){public net.minecraft.world.level.Level getLevel(){return level;}}
}

