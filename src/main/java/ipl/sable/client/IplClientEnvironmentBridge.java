package ipl.sable.client;

import ipl.sable.dim.IplClientWorldFrameContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** Keep ClientLevel out of the common environmental boundary's bytecode signatures. */
public final class IplClientEnvironmentBridge {
    private IplClientEnvironmentBridge() {}

    @Nullable
    public static Level current() {
        return IplClientWorldFrameContext.current();
    }
}