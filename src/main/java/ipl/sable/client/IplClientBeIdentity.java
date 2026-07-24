package ipl.sable.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * THE CLIENT IDENTITY FIX — restore stock Sable's Level-identity invariant for hosted
 * plot block entities.
 *
 * <p>Stock Sable keeps a ship's plot chunks in the SAME level the ship occupies, so every
 * mod-facing identity — {@code be.getLevel()}, {@code Minecraft.level}, the container level —
 * is one object. Rehoming plots into {@code ipl_sable:sublevels} split that identity, and
 * every sub-level mod keys something off it:
 * <ul>
 *   <li>{@code LevelRenderer.getLightColor(be.getLevel(), worldPos)} sampled the void
 *       dimension at world-frame positions → light 0 → ropes and other world-frame BE
 *       geometry rendered black/invisible;</li>
 *   <li>{@code WorldAttached} registries (Simulated's {@code ClientLevelRopeManager}) were
 *       keyed under the void level while {@code mc.level}-keyed lookups (ziplines) and the
 *       parent container's interpolation tick looked in the parent's bucket;</li>
 *   <li>{@code SubLevelContainer.getContainer(be.getLevel())} vs
 *       {@code getContainer(minecraft.level)} resolved two different containers;</li>
 *   <li>Flywheel's {@code VisualizationManager.supportsVisualization(be.getLevel())} gates
 *       vanished renderers behind a visualization world that never draws.</li>
 * </ul>
 *
 * <p>The fix: on the CLIENT, {@code BlockEntity.getLevel()} for a hosted plot BE returns the
 * owning sub-level's PARENT {@code ClientLevel} — the dimension the ship is actually in
 * (see {@code IplHostedBeLevelIdentityMixin}). The private {@code level} field, chunk
 * storage, ticking infrastructure and packet application all stay on the hosting level;
 * only the observable identity is restored to stock semantics. Plot-coordinate BLOCK reads
 * from the parent already resolve through Sable's client chunk-cache mixins + the plot
 * bridge ({@code IplPlotBridgeMixin}); plot-coordinate LIGHT reads are bridged by
 * {@code IplPlotLightBridgeMixin}.
 *
 * <p>The server keeps its field identity (save/tick machinery owns it) and routes
 * contextually instead ({@code IplHostedWorldFrameRouterMixin}).
 */
public final class IplClientBeIdentity {

    private IplClientBeIdentity() {}

    /**
     * Map the hosting {@code ClientLevel} to the parent {@code ClientLevel} of the sub-level
     * owning the plot at {@code pos}. Returns {@code original} untouched for anything that
     * isn't a hosted plot position with a resolvable client parent.
     *
     * <p>Hot path (every {@code getLevel()} call on the client): the non-hosting early-outs
     * are two field reads and a dimension-key identity compare.
     */
    public static Level mapHostedBeLevel(Level original, BlockPos pos) {
        if (!IplDimAgnostic.isHostingLevel(original)) {
            return original;
        }
        // Plot-grid coords are in the millions; nothing else on the hosting level has BEs.
        if (Math.abs(pos.getX()) < 1_000_000 && Math.abs(pos.getZ()) < 1_000_000) {
            return original;
        }
        ClientLevel parent = resolveParentForPlotPos(original, pos);
        return parent != null ? parent : original;
    }

    /** Owning sub-level's parent ClientLevel for a plot position on the hosting level. */
    @Nullable
    public static ClientLevel resolveParentForPlotPos(Level hosting, BlockPos pos) {
        SubLevelContainer container = SubLevelContainer.getContainer(hosting);
        if (container == null) return null;
        LevelPlot plot = container.getPlot(pos.getX() >> 4, pos.getZ() >> 4);
        if (plot == null) return null;
        SubLevel subLevel = plot.getSubLevel();
        if (subLevel == null || subLevel.isRemoved()) return null;
        Level parent = ((ipl.sable.duck.IplSubLevelDuck) subLevel).ipl$getParentLevel();
        return parent instanceof ClientLevel clientParent && !IplDimAgnostic.isHostingLevel(clientParent)
            ? clientParent : null;
    }
}
