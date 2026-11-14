package ipl.sable.render;

import dev.engine_room.flywheel.api.visualization.VisualManager;
import dev.engine_room.flywheel.impl.visualization.VisualManagerImpl;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.engine_room.flywheel.impl.visualization.storage.BlockEntityStorage;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.neoforge.mixinhelper.compatibility.flywheel.SubLevelEmbedding;
import dev.ryanhcode.sable.neoforge.mixinterface.compatibility.flywheel.BlockEntityStorageExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplParentDimSync;
import ipl.sable.render.route.IplOpenings;
import ipl.sable.render.route.IplPortalModels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * Flywheel visuals (Create cogs, shafts, any instanced block-entity visual) near portals.
 *
 * <p>Flywheel instances a visual in one environment at its real pose and only in the pass of
 * its own level: it can neither split a model at an opening nor draw it beyond a portal. So,
 * per visual, Flywheel gives a block entity up ({@code queueRemove}) while
 * <ul>
 *   <li>its model passes an opening ({@link IplPortalModels#isRouted}, world and bodies alike):
 *       its renderer is recorded and routed;</li>
 *   <li>it belongs to a straddling body's piece other than the native one: the piece pass of its
 *       body draws it at the piece's mapped pose ({@link IplSubLevelBlockEntityPass}).</li>
 * </ul>
 * It is given back ({@code queueAdd}) once neither holds. Removal and addition apply on
 * Flywheel's next frame; until then the visual stays Flywheel's, and in between the renderer
 * draws it: never by both, never by none. Everything else keeps instancing.
 *
 * <p>The environment of a hidden transit successor is collapsed as a whole
 * ({@code FlywheelEmbeddedEnvironmentMixin}). Loaded only with Flywheel present.
 */
public final class IplFlywheelFallback {

    private IplFlywheelFallback() {}

    /** Environments collapsed in the current pass. Render thread only. */
    private static final Set<Object> HIDDEN = Collections.newSetFromMap(new IdentityHashMap<>());
    /**
     * Last known embedding per sub-level, for projections into ANOTHER dimension: there
     * {@code VisualizationManagerImpl.get(source level)} is null (Flywheel serves only
     * {@code Minecraft.level}) and nothing of the source draws its visuals.
     */
    private static final Map<ClientSubLevel, SubLevelEmbedding> EMBEDDINGS = new WeakHashMap<>();
    /** Block entities taken out of Flywheel, per sub-level of the current level. */
    private static final Map<ClientSubLevel, Taken> TAKEN = new WeakHashMap<>();
    /** A body this close to an opening may have models that pass it (long models reach out of the body). */
    private static final double BODY_REACH = 16.0;
    /** Block entities of the current level's world taken out of Flywheel. */
    private static Taken worldTaken = new Taken();
    @Nullable private static Level worldTakenLevel;

    private static final class Taken {
        /** Removal queued: the renderer draws them once Flywheel has dropped them. */
        final Set<BlockEntity> out = identitySet();
        /** Addition queued: the renderer keeps drawing them until Flywheel has them again. */
        final Set<BlockEntity> back = identitySet();
        /** What the body's piece passes draw this frame: taken and not visualized. */
        List<BlockEntity> draw = List.of();

        boolean isEmpty() {
            return out.isEmpty() && back.isEmpty();
        }
    }

    private static <T> Set<T> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /** HEAD {@code EnvironmentStorage.flush()}: the current level's Flywheel draws a frame. */
    public static void beginEnvironmentFlush() {
        HIDDEN.clear();
        ClientLevel level = Minecraft.getInstance().level;
        VisualizationManagerImpl manager = VisualizationManagerImpl.get(level);
        if (manager == null) return;
        VisualManager<BlockEntity> visuals = manager.blockEntities();
        BlockEntityStorage storage = storage(visuals);
        updateBodies(level, visuals, storage);
        updateWorld(level, visuals, storage);
    }

    private static void updateBodies(ClientLevel level, VisualManager<BlockEntity> visuals, BlockEntityStorage storage) {
        if (!(SubLevelContainer.getContainer(level) instanceof ClientSubLevelContainer container)) return;
        // Flywheel's BE storage carries Sable's extension (its compat mixin), as in Simulated.
        BlockEntityStorageExtension embeddings = (BlockEntityStorageExtension) storage;
        for (ClientSubLevel sub : container.getAllSubLevels()) {
            SubLevelEmbedding info = embeddings.sable$getEmbeddingInfo(sub);
            if (info == null) EMBEDDINGS.remove(sub);
            else EMBEDDINGS.put(sub, info);
            if (IplParentDimSync.isSuppressedSuccessor(sub)) {
                if (info != null) HIDDEN.add(info.embedding());
                continue;
            }
            IplPieceRender.Frame frame = IplPieceRender.frameFor(sub);
            Taken taken = TAKEN.get(sub);
            if (frame == null && taken == null && !IplOpenings.anyNear(level, sub.boundingBox().toMojang(), BODY_REACH)) continue;
            if (taken == null) TAKEN.put(sub, taken = new Taken());
            Collection<BlockEntity> visualized = info == null ? List.of() : info.blockEntities();
            update(visuals, keeper(frame), visualized, taken);
            if (frame == null && taken.isEmpty()) TAKEN.remove(sub);
        }
    }

    /** World block entities whose models pass an opening (found by the routing's candidate scan). */
    private static void updateWorld(ClientLevel level, VisualManager<BlockEntity> visuals, BlockEntityStorage storage) {
        if (worldTakenLevel != level) {
            worldTaken = new Taken();
            worldTakenLevel = level;
        }
        List<BlockEntity> visualized = new ArrayList<>();
        for (BlockEntity be : IplPortalModels.candidates(level)) {
            if (Sable.HELPER.getContainingClient(be) == null && storage.visualAtPos(be.getBlockPos().asLong()) != null) {
                visualized.add(be);
            }
        }
        update(visuals, be -> !IplPortalModels.isRouted(be), visualized, worldTaken);
    }

    /**
     * Whether Flywheel may keep drawing a block entity: not when its model passes an opening,
     * nor, for a straddling body, when its block is outside the native piece.
     */
    private static Predicate<BlockEntity> keeper(@Nullable IplPieceRender.Frame frame) {
        return be -> !IplPortalModels.isRouted(be) && (frame == null || IplPieceRender.isNativeBlockEntity(frame, be));
    }

    private static void update(VisualManager<BlockEntity> visuals, Predicate<BlockEntity> keeps,
                               Collection<BlockEntity> visualizedNow, Taken taken) {
        Set<BlockEntity> visualized = identitySet();
        visualized.addAll(visualizedNow);
        for (BlockEntity be : visualized) {
            if (keeps.test(be)) continue;
            visuals.queueRemove(be);
            taken.out.add(be);
            taken.back.remove(be);
            IplPortalModels.notice(be);
        }
        for (Iterator<BlockEntity> it = taken.out.iterator(); it.hasNext(); ) {
            BlockEntity be = it.next();
            if (be.isRemoved()) {
                it.remove();
            } else if (keeps.test(be)) {
                visuals.queueAdd(be);
                it.remove();
                taken.back.add(be);
                IplPortalModels.notice(be);
            }
        }
        // Not accepted again (Flywheel refused it) or already back: nothing left to cover.
        taken.back.removeIf(be -> be.isRemoved() || visualized.contains(be));
        List<BlockEntity> draw = new ArrayList<>(taken.out.size() + taken.back.size());
        for (BlockEntity be : taken.out) if (!visualized.contains(be)) draw.add(be);
        draw.addAll(taken.back);
        taken.draw = draw;
    }

    @SuppressWarnings("unchecked")
    private static BlockEntityStorage storage(VisualManager<BlockEntity> visuals) {
        return ((VisualManagerImpl<BlockEntity, BlockEntityStorage>) visuals).getStorage();
    }

    public static boolean isHiddenEnvironment(Object environment) {
        return HIDDEN.contains(environment);
    }

    /** Whether Flywheel draws {@code be} this frame (only the current level's Flywheel draws anything). */
    public static boolean isVisualized(BlockEntity be) {
        Level level = be.getLevel();
        if (level == null || level != Minecraft.getInstance().level) return false;
        VisualizationManagerImpl manager = VisualizationManagerImpl.get(level);
        return manager != null && storage(manager.blockEntities()).visualAtPos(be.getBlockPos().asLong()) != null;
    }

    /**
     * Whether a world block entity was given back to Flywheel and is not drawn by it yet (the
     * routing keeps drawing it). Bodies' returning block entities are drawn by their body pass.
     */
    public static boolean isReturning(BlockEntity be) {
        return worldTaken.back.contains(be);
    }

    /** Block entities of {@code sub} whose Flywheel visual is not drawn and that its piece passes must draw. */
    public static List<BlockEntity> visualizedBlockEntities(ClientSubLevel sub) {
        Taken taken = TAKEN.get(sub);
        if (taken != null) return taken.draw;
        if (sub.getLevel() == Minecraft.getInstance().level) return List.of();
        SubLevelEmbedding info = EMBEDDINGS.get(sub);
        return info == null || info.blockEntities().isEmpty() ? List.of() : new ArrayList<>(info.blockEntities());
    }

    public static boolean hasVisualized(ClientSubLevel sub) {
        Taken taken = TAKEN.get(sub);
        if (taken != null) return !taken.draw.isEmpty();
        if (sub.getLevel() == Minecraft.getInstance().level) return false;
        SubLevelEmbedding info = EMBEDDINGS.get(sub);
        return info != null && !info.blockEntities().isEmpty();
    }
}
