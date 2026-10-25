package dev.ryanhcode.sable.api.sublevel;
public class SubLevelContainer {
public Plot plot;public static SubLevelContainer getContainer(net.minecraft.world.level.Level l){return l.container;}public Plot getPlot(int x,int z){return plot;}
public static class Sub {public boolean removed;public boolean isRemoved(){return removed;}}
public static class Plot {public Sub sub=new Sub();public Holder holder;public Sub getSubLevel(){return sub;}public net.minecraft.world.level.ChunkPos toLocal(net.minecraft.world.level.ChunkPos p){return p;}public Holder getChunkHolder(net.minecraft.world.level.ChunkPos p){return holder;}}
public static class Holder {public Chunk chunk;public Chunk getChunk(){return chunk;}}
public static class Chunk {public final java.util.Map<net.minecraft.core.BlockPos,net.minecraft.world.level.block.entity.BlockEntity> bes=new java.util.HashMap<>();public final net.minecraft.world.level.block.state.BlockState state=new net.minecraft.world.level.block.state.BlockState();public java.util.Map<net.minecraft.core.BlockPos,net.minecraft.world.level.block.entity.BlockEntity> getBlockEntities(){return bes;}public net.minecraft.world.level.block.state.BlockState getBlockState(net.minecraft.core.BlockPos p){return state;}}
}

