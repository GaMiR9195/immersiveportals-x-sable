package net.minecraft.world.level;
public interface LevelHeightAccessor { int getMinBuildHeight(); int getMaxBuildHeight();
 default boolean isOutsideBuildHeight(net.minecraft.core.BlockPos p){return p.getY()<getMinBuildHeight()||p.getY()>=getMaxBuildHeight();}}

