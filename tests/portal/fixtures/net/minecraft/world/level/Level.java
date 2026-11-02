package net.minecraft.world.level;
import net.minecraft.resources.ResourceKey;
/** One fixture dimension; every fixture portal lives in it and leads back into it. */
public class Level {
 public static final ResourceKey<Level> FIXTURE = new ResourceKey<>();
 public static final Level INSTANCE = new Level();
 public ResourceKey<Level> dimension(){return FIXTURE;}
}
