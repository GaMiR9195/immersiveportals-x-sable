package ipl.sable.client;
import ipl.sable.dim.IplClientWorldFrameContext;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;

/**
 * Object-scoped VisualizationManager view. Never changes a Level getter or global manager
 * identity: only a query made FOR a hosted object (its level is the hosting world) is
 * answered for the world that object is drawn into.
 *
 * <ul>
 *   <li>Lifecycle frames ({@link #enter}): chunk BE add, BE removal, block dirty — the
 *       object's plot position names its ship, hence its parent.</li>
 *   <li>Client world frames ({@link IplClientWorldFrameContext}): hosted BE render passes and
 *       hosted BE ticks already name the world being drawn/acted in. Renderers that ask
 *       {@code VisualizationManager.supportsVisualization(be.getLevel())} to skip parts a
 *       Flywheel visual draws (stock Create pattern) get the same answer as on a stock ship.</li>
 * </ul>
 */
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
        if (frame != null && frame.source == source) return frame.view;
        if (source instanceof Level level && IplDimAgnostic.isHostingLevel(level)) {
            ClientLevel world = IplClientWorldFrameContext.current();
            if (world != null && world != source && !IplDimAgnostic.isHostingLevel(world)) return world;
        }
        return source;
    }
}
