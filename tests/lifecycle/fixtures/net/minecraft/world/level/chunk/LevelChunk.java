package net.minecraft.world.level.chunk;
import net.minecraft.world.level.ChunkPos;
public class LevelChunk {
 private final ChunkPos pos; private final LevelChunkSection[] sections=new LevelChunkSection[32];
 public LevelChunk(int x,int z){pos=new ChunkPos(x,z);for(int i=0;i<sections.length;i++)sections[i]=new LevelChunkSection();}
 public ChunkPos getPos(){return pos;} public LevelChunkSection[] getSections(){return sections;}
 public int getSectionYFromSectionIndex(int i){return i-16;}
 public LevelChunkSection section(int sy){return sections[sy+16];}
}
