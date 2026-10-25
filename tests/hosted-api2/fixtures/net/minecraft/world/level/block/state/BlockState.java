package net.minecraft.world.level.block.state;
public class BlockState implements dev.ryanhcode.sable.mixinterface.block_properties.BlockStateExtension {
 public final net.minecraft.world.level.block.Block block;public final boolean persistent,fragile;
 public BlockState(net.minecraft.world.level.block.Block b,boolean p,boolean f){block=b;persistent=p;fragile=f;}
 public net.minecraft.world.level.block.Block getBlock(){return block;}public boolean getValue(Object key){return persistent;}
 public boolean sable$getProperty(Object property){return fragile;}public boolean blocksMotion(){return true;}public boolean liquid(){return false;}
}

