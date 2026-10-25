package dev.ryanhcode.sable.sublevel;
public final class ClientSubLevel {
 public Object data; public final Plot plot=new Plot();public Object getRenderData(){return data;}public Plot getPlot(){return plot;}
 public static class Plot { public final java.util.List<Holder> holders=new java.util.ArrayList<>();public java.util.List<Holder> getLoadedChunks(){return holders;} }
 public static class Holder {public Chunk chunk;public Chunk getChunk(){return chunk;} }
 public static class Chunk {public final java.util.Map<Integer,BE> bes=new java.util.HashMap<>();public java.util.Map<Integer,BE> getBlockEntities(){return bes;} }
 public static class BE {public boolean removed; public boolean isRemoved(){return removed;} }
}
