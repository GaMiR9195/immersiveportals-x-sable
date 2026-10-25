package net.minecraft.world.level; public record ChunkPos(int x,int z){public ChunkPos(net.minecraft.core.BlockPos p){this(p.x()>>4,p.z()>>4);}}
