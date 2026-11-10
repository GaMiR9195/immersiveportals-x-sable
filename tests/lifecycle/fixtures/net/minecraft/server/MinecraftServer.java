package net.minecraft.server;
import java.util.*; import net.minecraft.server.level.ServerLevel;
public class MinecraftServer {public final Map<String,ServerLevel> levels=new HashMap<>(); public ServerLevel getLevel(String id){return levels.get(id);} public int getTickCount(){return 1;}}
