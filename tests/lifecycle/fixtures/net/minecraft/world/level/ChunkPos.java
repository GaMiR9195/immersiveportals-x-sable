package net.minecraft.world.level;
import net.minecraft.core.BlockPos;
public class ChunkPos {public final int x,z;public ChunkPos(int x,int z){this.x=x;this.z=z;}public ChunkPos(BlockPos p){this(p.getX()>>4,p.getZ()>>4);}}
