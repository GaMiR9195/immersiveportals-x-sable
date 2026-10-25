package net.minecraft.world.level.block.entity;
public class BlockEntity {public boolean removed;public net.minecraft.world.level.Level level;public net.minecraft.core.BlockPos pos=new net.minecraft.core.BlockPos(32000,64,32000);
public boolean isRemoved(){return removed;}public net.minecraft.world.level.Level getLevel(){return level;}public net.minecraft.core.BlockPos getBlockPos(){return pos;}
public Type getType(){return new Type();}public static class Type {public boolean isValid(net.minecraft.world.level.block.state.BlockState s){return s.valid;}}}

