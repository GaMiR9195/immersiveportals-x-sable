package net.minecraft.server.level;
public class ServerLevel extends net.minecraft.world.level.Level {
 public net.minecraft.server.MinecraftServer server;
 public final java.util.Map<net.minecraft.core.BlockPos,net.minecraft.world.level.block.state.BlockState> blocks=new java.util.HashMap<>();
 public final java.util.List<net.minecraft.core.BlockPos> destroyed=new java.util.ArrayList<>();
 public ServerLevel(boolean s){super(s);}public net.minecraft.server.MinecraftServer getServer(){return server;}
 public net.minecraft.world.level.block.state.BlockState getBlockState(net.minecraft.core.BlockPos pos){return blocks.getOrDefault(pos,new net.minecraft.world.level.block.state.BlockState(new net.minecraft.world.level.block.Block(),false,false));}
 public boolean destroyBlock(net.minecraft.core.BlockPos pos,boolean drops){destroyed.add(pos);blocks.remove(pos);return true;}
 public boolean setBlockAndUpdate(net.minecraft.core.BlockPos pos,net.minecraft.world.level.block.state.BlockState state){blocks.put(pos,state);return true;}
}

