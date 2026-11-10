package net.minecraft.core;
public record BlockPos(int x,int y,int z) {
 public int getX(){return x;}public int getY(){return y;}public int getZ(){return z;}
 public static long asLong(int x,int y,int z){return ((long)x&0x3ffffffL)<<38|((long)z&0x3ffffffL)<<12|((long)y&0xfffL);}
 public static int getX(long p){return (int)(p>>38);}public static int getY(long p){return (int)(p<<52>>52);}public static int getZ(long p){return (int)(p<<26>>38);}
}
