package ipl.sable.client;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.impl.visualization.VisualManagerImpl;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.mixin.client.IplFlywheelStorageAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Flywheel visuals of hosted ship content, kept on Flywheel's own lifecycle.
 *
 * <p>A hosted ship's block entities and entities are stored in the hosting client world,
 * but they are drawn in the world the ship flies through, so their visuals belong to that
 * world's {@code VisualizationManager}. Flywheel's stock lifecycle already reaches it:
 * chunk block-entity add, block-entity removal and block-dirty updates resolve
 * {@code VisualizationManager.get} through the object-scoped visual view
 * ({@link IplClientVisualViewContext}); {@code VisualizationHelper} (section compile, Sodium
 * render predicates, Sable's single-block path) and entity join/leave resolve the containing
 * ship's parent; Sable's per-ship embedding follows the ship's render pose.
 *
 * <p>One event has no stock hook, because in stock Sable a ship's content never changes
 * worlds: a ship entering or leaving a world's visual scene — its parent becoming known after
 * its plot arrived, a portal transit, or a world's manager being (re)created after a backend
 * or resource reload (Flywheel then repopulates from the world itself, which does not store
 * the hosted content). {@link #reconcile} handles exactly that membership, per manager and
 * per frame, by offering or withdrawing the ship's current content through Flywheel's public
 * API. Nothing is tracked per block entity or per entity.
 */
public final class IplHostedFlywheelVisuals {

    private IplHostedFlywheelVisuals() {}

    /** The world whose manager holds {@code entity}'s visual: its hosted ship's parent. */
    public static Level entityView(Level fallback, Entity entity) {
        SubLevel sub = Sable.HELPER.getContaining(entity);
        Level parent = sceneOf(sub);
        return parent != null ? parent : fallback;
    }

    /**
     * Called as {@code manager} begins a frame of {@code world}. {@code present} is the
     * manager's own set of hosted ships whose content it has been offered.
     */
    public static void reconcile(VisualizationManager manager, LevelAccessor world, Set<SubLevel> present) {
        if (!(world instanceof ClientLevel level) || IplDimAgnostic.isHostingLevel(level)) return;

        List<SubLevel> leaving = null;
        for (Iterator<SubLevel> it = present.iterator(); it.hasNext(); ) {
            SubLevel sub = it.next();
            if (sub.isRemoved() || sceneOf(sub) != level) {
                it.remove();
                // A removed ship's block-entity visuals are retired with its embedding
                // (Sable) and its block entities' liveness (IplHostedFlywheelStorageMixin).
                if (!sub.isRemoved()) {
                    if (leaving == null) leaving = new ArrayList<>();
                    leaving.add(sub);
                }
            }
        }

        List<SubLevel> entering = null;
        SubLevelContainer hosting = IplDimAgnostic.getHostingContainerFor(level);
        if (hosting != null && hosting.getLevel() != level) {
            for (SubLevel sub : hosting.getAllSubLevels()) {
                if (sub == null || sub.isRemoved() || sceneOf(sub) != level || !present.add(sub)) continue;
                if (entering == null) entering = new ArrayList<>();
                entering.add(sub);
            }
        }

        if (leaving != null) {
            for (SubLevel sub : leaving) {
                forEachBlockEntity(sub, be -> manager.blockEntities().queueRemove(be));
            }
            forEachEntity(leaving, (sub, entity) -> manager.entities().queueRemove(entity));
        }
        if (entering != null) {
            for (SubLevel sub : entering) {
                forEachBlockEntity(sub, be -> manager.blockEntities().queueAdd(be));
            }
            Set<SubLevel> withEntities = Collections.newSetFromMap(new IdentityHashMap<>());
            forEachEntity(entering, (sub, entity) -> {
                // Sable's per-ship render state for embedded entity visuals (contraptions)
                // is created at entity join; a join can precede the ship's plot sync.
                if (withEntities.add(sub)) {
                    dev.ryanhcode.sable.neoforge.compatibility.flywheel.FlywheelCompatNeoForge
                        .createRenderInfo(sub.getLevel(), sub);
                }
                manager.entities().queueAdd(entity);
            });
        }

        retireRemovedHostedEntities(manager);
    }

    /**
     * An entity leaving the hosting world after its ship is gone has no resolvable scene, so
     * its stock leave event reaches no manager. Retire such visuals from the storage itself.
     */
    private static void retireRemovedHostedEntities(VisualizationManager manager) {
        if (!(manager.entities() instanceof VisualManagerImpl<?, ?> impl)) return;
        var visuals = ((IplFlywheelStorageAccessor) impl.getStorage()).ipl$visuals();
        if (visuals.isEmpty()) return;
        List<Entity> retired = null;
        for (Object key : visuals.keySet()) {
            if (key instanceof Entity entity && entity.isRemoved()
                && IplDimAgnostic.isHostingLevel(entity.level())) {
                if (retired == null) retired = new ArrayList<>();
                retired.add(entity);
            }
        }
        if (retired != null) retired.forEach(manager.entities()::queueRemove);
    }

    /** The client world a hosted ship is drawn into, or null if it is not hosted/synced. */
    @Nullable
    private static Level sceneOf(@Nullable SubLevel sub) {
        if (sub == null || sub.isRemoved() || !IplDimAgnostic.isHosted(sub)) return null;
        Level parent = IplDimAgnostic.getParentLevel(sub);
        return parent instanceof ClientLevel && !IplDimAgnostic.isHostingLevel(parent) ? parent : null;
    }

    private static void forEachBlockEntity(SubLevel sub, java.util.function.Consumer<BlockEntity> action) {
        for (PlotChunkHolder holder : sub.getPlot().getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk == null) continue;
            for (BlockEntity be : new ArrayList<>(chunk.getBlockEntities().values())) {
                if (be != null && !be.isRemoved()) action.accept(be);
            }
        }
    }

    private static void forEachEntity(List<SubLevel> ships,
                                      java.util.function.BiConsumer<SubLevel, Entity> action) {
        Level storage = ships.get(0).getLevel();
        if (!(storage instanceof ClientLevel client)) return;
        Set<SubLevel> wanted = Collections.newSetFromMap(new IdentityHashMap<>());
        wanted.addAll(ships);
        for (Entity entity : client.entitiesForRendering()) {
            if (entity == null || entity.isRemoved()) continue;
            SubLevel sub = Sable.HELPER.getContaining(entity);
            if (sub != null && wanted.contains(sub)) action.accept(sub, entity);
        }
    }
}
