package dev.ryanhcode.sable.sublevel;
import java.util.UUID; import dev.ryanhcode.sable.sublevel.plot.LevelPlot; import net.minecraft.server.level.ServerLevel;
public abstract class SubLevel {public abstract UUID getUniqueId();public abstract LevelPlot getPlot();public abstract ServerLevel getLevel();public abstract boolean isRemoved();}
