package ipl.sable.client;
import ipl.sable.dim.IplClientWorldFrameContext;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
/** Object-scoped VisualizationManager view. Never changes a Level getter or global manager identity. */
public final class IplClientVisualViewContext {
    public record Frame(Level source,Level view) {}
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    private IplClientVisualViewContext() {}
    public static Frame enter(Level source,BlockPos pos) {
        Frame previous = CURRENT.get();
        Level parent = source instanceof ClientLevel client && IplDimAgnostic.isHostingLevel(source) && pos != null
            ? IplClientWorldFrameContext.resolveParentForPlotBe(client,pos) : null;
        if (parent == null || parent == source) CURRENT.remove();
        else CURRENT.set(new Frame(source,parent));
        return previous;
    }
    public static void exit(Frame previous) { if(previous==null) CURRENT.remove();else CURRENT.set(previous); }
    public static LevelAccessor resolve(LevelAccessor source) {
        Frame frame = CURRENT.get();
        return frame != null && frame.source == source ? frame.view : source;
    }
}
