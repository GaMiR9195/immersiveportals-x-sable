package net.minecraft.world.level.chunk;
import net.minecraft.world.level.block.state.BlockState;
public class LevelChunkSection {
 public final boolean[] solid=new boolean[4096];
 public boolean hasOnlyAir(){for(boolean s:solid)if(s)return false;return true;}
 public BlockState getBlockState(int x,int y,int z){return new BlockState(!solid[(x&15)|(y&15)<<4|(z&15)<<8]);}
 public void set(int x,int y,int z){solid[(x&15)|(y&15)<<4|(z&15)<<8]=true;}
}
