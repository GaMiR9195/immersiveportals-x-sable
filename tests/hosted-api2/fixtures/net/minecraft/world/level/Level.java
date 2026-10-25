package net.minecraft.world.level;
public class Level implements LevelAccessor,LevelHeightAccessor {
 public final boolean storage; public int min=-64,max=320;
 public Level(boolean s){storage=s;} public int getMinBuildHeight(){return min;}public int getMaxBuildHeight(){return max;}
}
