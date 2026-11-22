package ipl.sable.render;

import dev.engine_room.flywheel.api.visualization.VisualManager;
import dev.engine_room.flywheel.impl.visualization.VisualManagerImpl;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.engine_room.flywheel.impl.visualization.storage.BlockEntityStorage;
import dev.engine_room.flywheel.impl.visualization.storage.Storage;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.neoforge.mixinhelper.compatibility.flywheel.SubLevelEmbedding;
import dev.ryanhcode.sable.neoforge.mixinterface.compatibility.flywheel.BlockEntityStorageExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplParentDimSync;
import ipl.sable.mixin.client.FlywheelStorageAccessor;
import ipl.sable.render.route.IplOpenings;
import ipl.sable.render.route.IplPortalModelDiagnostics;
import ipl.sable.render.route.IplPortalModels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Flywheel visuals (Create cogs, shafts, contraptions, any instanced visual) near portals.
 *
 * <p>Flywheel instances a visual in one environment at its real pose and only in the pass of
 * its own level: it can neither split a model at an opening nor draw it beyond a portal. So a
 * block entity is handed to its renderer while
 * <ul>
 *   <li>its model comes near an opening ({@link IplPortalModels#isNearOpening}, world and bodies
 *       alike) — routed through the opening once it crosses;</li>
 *   <li>it belongs to a straddling body's piece other than the native one — the piece pass of
 *       its body draws it at the piece's mapped pose ({@link IplSubLevelBlockEntityPass}).</li>
 * </ul>
 * and an entity routed by {@link IplPortalModels} (a contraption) while it is near an opening or
 * held beyond a portal of its straddling body.
 *
 * <p>How Flywheel keeps visuals decides how this works:
 * <ul>
 *   <li>Flywheel re-adds block entities on its own (every section compile through
 *       {@code VisualizationHelper.tryAddBlockEntity}, block updates, Sable's one-block bodies),
 *       and entities when they join a level. A handed object is therefore <em>vetoed</em>:
 *       Flywheel's storage refuses it until it is given back ({@code FlywheelStorageVetoMixin}).</li>
 *   <li>Queued removals and additions apply in Flywheel's next frame plan, which may run on worker
 *       threads while the world draws. Flywheel's state is read only here, at its flush (the plan
 *       is synced), and the renderer's turn follows the queue: vetoed at a flush → the renderer
 *       draws from the next frame (the visual is gone by then); given back → the renderer keeps
 *       drawing until a later flush finds the visual in Flywheel's record. Never none.</li>
 *   <li>A handed visual goes back only after it has not been wanted for {@link #RELEASE_NANOS}:
 *       a model hovering at the threshold (a body sliding along a portal, a vibrating link) is
 *       not handed back and forth every frame.</li>
 *   <li>Flywheel's record is its {@code visuals} map, not {@code visualAtPos}: Sable's storage
 *       mixin never clears {@code posLookup} on removal ({@code FlywheelStorageAccessor}).</li>
 * </ul>
 * Everything else keeps instancing. The environment of a hidden transit successor is collapsed
 * as a whole ({@code FlywheelEmbeddedEnvironmentMixin}). Loaded only with Flywheel present.
 */
public final class IplFlywheelFallback {

    private IplFlywheelFallback() {}

    /** A body this close to an opening may have models that reach it (long models reach out of the body). */
    private static final double BODY_REACH = 16.0;
    /** How long a handed object must stay unwanted before it goes back to Flywheel. */
    private static final long RELEASE_NANOS = 750_000_000L;

    /** Environments collapsed in the current pass. Render thread only. */
    private static final Set<Object> HIDDEN = Collections.newSetFromMap(new IdentityHashMap<>());
    /**
     * Last known embedding per sub-level, for projections into ANOTHER dimension: there
     * {@code VisualizationManagerImpl.get(source level)} is null (Flywheel serves only
     * {@code Minecraft.level}) and nothing of the source draws its visuals.
     */
    private static final Map<ClientSubLevel, SubLevelEmbedding> EMBEDDINGS = new WeakHashMap<>();
    /** Handed block entities per body of the current level (for its body passes). */
    private static final Map<ClientSubLevel, List<BlockEntity>> BY_BODY = new WeakHashMap<>();

    private static final Handover<BlockEntity> BLOCK_ENTITIES = new Handover<>(BlockEntity::getLevel, BlockEntity::isRemoved);
    private static final Handover<Entity> ENTITIES = new Handover<>(Entity::level, Entity::isRemoved);

    /** Whether Flywheel must refuse {@code be} now ({@code FlywheelStorageVetoMixin}). */
    public static boolean isVetoed(BlockEntity be) {
        return BLOCK_ENTITIES.vetoed.contains(be);
    }

    /** Whether Flywheel must refuse {@code entity} now ({@code FlywheelStorageVetoMixin}). */
    public static boolean isVetoed(Entity entity) {
        return ENTITIES.vetoed.contains(entity);
    }

    /** HEAD {@code EnvironmentStorage.flush()}: the current level's Flywheel draws a frame (its plan is synced). */
    public static void beginEnvironmentFlush() {
        HIDDEN.clear();
        ClientLevel level = Minecraft.getInstance().level;
        VisualizationManagerImpl manager = VisualizationManagerImpl.get(level);
        if (manager == null) return;
        VisualManager<BlockEntity> visuals = manager.blockEntities();
        BlockEntityStorage storage = (BlockEntityStorage) storage(visuals);
        Set<BlockEntity> wanted = Collections.newSetFromMap(new IdentityHashMap<>());
        collectBodies(level, storage, wanted);
        for (BlockEntity be : IplPortalModels.candidates(level)) {
            if (Sable.HELPER.getContainingClient(be) == null && IplPortalModels.isNearOpening(be)) wanted.add(be);
        }
        BLOCK_ENTITIES.reconcile(level, visuals, record(storage), wanted);
        indexBodies(level);

        VisualManager<Entity> entityVisuals = manager.entities();
        Set<Entity> wantedEntities = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Entity entity : IplPortalModels.entityCandidates(level)) {
            if (IplPortalModels.isNearOpening(entity) || IplPortalModels.isHeldBeyondPortal(entity)) wantedEntities.add(entity);
        }
        ENTITIES.reconcile(level, entityVisuals, record(storage(entityVisuals)), wantedEntities);
    }

    /** Block entities of the level's bodies that their renderers must draw. */
    private static void collectBodies(ClientLevel level, BlockEntityStorage storage, Set<BlockEntity> wanted) {
        if (!(SubLevelContainer.getContainer(level) instanceof ClientSubLevelContainer container)) return;
        // Flywheel's BE storage carries Sable's extension (its compat mixin).
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
            List<BlockEntity> handed = BY_BODY.get(sub);
            if (frame == null && handed == null && !IplOpenings.anyNear(level, sub.boundingBox().toMojang(), BODY_REACH)) continue;
            if (info != null) for (BlockEntity be : info.blockEntities()) if (rendererDraws(be, frame)) wanted.add(be);
            if (handed != null) for (BlockEntity be : handed) if (rendererDraws(be, frame)) wanted.add(be);
        }
    }

    /** Whether the renderer must draw {@code be}: near an opening, or outside the native piece of its straddling body. */
    private static boolean rendererDraws(BlockEntity be, @Nullable IplPieceRender.Frame frame) {
        if (be.isRemoved()) return false;
        return IplPortalModels.isNearOpening(be) || frame != null && !IplPieceRender.isNativeBlockEntity(frame, be);
    }

    private static void indexBodies(ClientLevel level) {
        BY_BODY.clear();
        for (Set<BlockEntity> set : List.of(BLOCK_ENTITIES.vetoed, BLOCK_ENTITIES.returning)) {
            for (BlockEntity be : set) {
                if (be.getLevel() != level) continue;
                ClientSubLevel sub = Sable.HELPER.getContainingClient(be);
                if (sub != null) BY_BODY.computeIfAbsent(sub, s -> new ArrayList<>()).add(be);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Storage<T> storage(VisualManager<T> visuals) {
        return ((VisualManagerImpl<T, ? extends Storage<T>>) visuals).getStorage();
    }

    private static Map<Object, ?> record(Storage<?> storage) {
        return ((FlywheelStorageAccessor) (Object) storage).ipl$visuals();
    }

    public static boolean isHiddenEnvironment(Object environment) {
        return HIDDEN.contains(environment);
    }

    /** Whether the renderer of {@code be} draws it this frame instead of Flywheel. */
    public static boolean isHanded(BlockEntity be) {
        return BLOCK_ENTITIES.isHanded(be);
    }

    /** Whether the renderer of {@code entity} draws it this frame instead of Flywheel. */
    public static boolean isHanded(Entity entity) {
        return ENTITIES.isHanded(entity);
    }

    /** A handed world block entity: the routing draws it. Bodies' are drawn by their body passes. */
    public static boolean isHandedOver(BlockEntity be) {
        return isHanded(be) && Sable.HELPER.getContainingClient(be) == null;
    }

    /** Block entities of {@code sub} whose Flywheel visual is not drawn and that its body passes must draw. */
    public static List<BlockEntity> visualizedBlockEntities(ClientSubLevel sub) {
        if (sub.getLevel() == Minecraft.getInstance().level) {
            List<BlockEntity> handed = BY_BODY.get(sub);
            return handed == null ? List.of() : handed;
        }
        SubLevelEmbedding info = EMBEDDINGS.get(sub);
        return info == null || info.blockEntities().isEmpty() ? List.of() : new ArrayList<>(info.blockEntities());
    }

    public static boolean hasVisualized(ClientSubLevel sub) {
        return !visualizedBlockEntities(sub).isEmpty();
    }

    /** Whether Flywheel draws the visual parts of {@code be} (its level is served and it is not handed). */
    public static boolean flywheelDraws(BlockEntity be) {
        Level level = be.getLevel();
        return level != null && Minecraft.getInstance().player != null
            && level == Minecraft.getInstance().player.level() && !isHanded(be);
    }

    /** What Flywheel must not visualize now, and what it is given back, for one kind of object. */
    private static final class Handover<T> {
        /** Read from any thread (Flywheel queues anywhere). */
        final Set<T> vetoed = ConcurrentHashMap.newKeySet();
        /** Given back and not yet found in Flywheel's record: their renderers keep drawing them. */
        final Set<T> returning = Collections.newSetFromMap(new WeakHashMap<>());
        /** When each handed object was last wanted by its renderer (render thread only). */
        private final Map<T, Long> lastWanted = new WeakHashMap<>();
        private final Function<T, Level> levelOf;
        private final Predicate<T> removed;

        Handover(Function<T, Level> levelOf, Predicate<T> removed) {
            this.levelOf = levelOf;
            this.removed = removed;
        }

        boolean isHanded(T object) {
            return vetoed.contains(object) || returning.contains(object);
        }

        /**
         * Vetoes what is wanted (removing its visual), gives back the rest of this level's vetoed
         * objects, and keeps re-queuing given-back ones until Flywheel's record has them.
         */
        void reconcile(ClientLevel level, VisualManager<T> visuals, Map<Object, ?> record, Set<T> wanted) {
            long now = System.nanoTime();
            for (T object : wanted) {
                if (removed.test(object)) continue;
                lastWanted.put(object, now);
                returning.remove(object);
                if (!vetoed.add(object)) continue;
                if (record.containsKey(object)) visuals.queueRemove(object);
                IplPortalModelDiagnostics.handOver(object, true);
            }
            for (T object : new ArrayList<>(vetoed)) {
                if (levelOf.apply(object) != level || wanted.contains(object)) continue;
                Long last = lastWanted.get(object);
                boolean gone = removed.test(object);
                if (!gone && last != null && now - last < RELEASE_NANOS) continue;
                vetoed.remove(object);
                lastWanted.remove(object);
                if (gone) continue;
                returning.add(object);
                IplPortalModels.notice(object);
                IplPortalModelDiagnostics.handOver(object, false);
            }
            returning.removeIf(object -> removed.test(object) || levelOf.apply(object) == level && record.containsKey(object));
            for (T object : returning) if (levelOf.apply(object) == level) visuals.queueAdd(object);
        }
    }
}
