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
import ipl.sable.mixin.client.FlywheelStorageAccessor;
import ipl.sable.render.route.IplOpenings;
import ipl.sable.render.route.IplPortalModelDiagnostics;
import ipl.sable.render.route.IplPortalModels;
import ipl.sable.transit.IplContraptions;
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

/**
 * Flywheel visuals (Create cogs, shafts, any instanced block-entity visual) near portals.
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
 *
 * <p>How Flywheel keeps visuals decides how this works:
 * <ul>
 *   <li>Flywheel re-adds block entities on its own (every section compile through
 *       {@code VisualizationHelper.tryAddBlockEntity}, block updates, Sable's one-block bodies).
 *       A handed block entity is therefore <em>vetoed</em>: Flywheel's storage refuses it until it
 *       is given back ({@code FlywheelStorageVetoMixin}).</li>
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
 * as a whole ({@code FlywheelEmbeddedEnvironmentMixin}). Contraption entities of straddling bodies
 * are handed over the same way ({@link IplContraptionRender}). Loaded only with Flywheel present.
 */
public final class IplFlywheelFallback {

    private IplFlywheelFallback() {}

    /** A body this close to an opening may have models that reach it (long models reach out of the body). */
    private static final double BODY_REACH = 16.0;
    /** How long a handed block entity must stay unwanted before it goes back to Flywheel. */
    private static final long RELEASE_NANOS = 750_000_000L;

    /** Environments collapsed in the current pass. Render thread only. */
    private static final Set<Object> HIDDEN = Collections.newSetFromMap(new IdentityHashMap<>());
    /**
     * Last known embedding per sub-level, for projections into ANOTHER dimension: there
     * {@code VisualizationManagerImpl.get(source level)} is null (Flywheel serves only
     * {@code Minecraft.level}) and nothing of the source draws its visuals.
     */
    private static final Map<ClientSubLevel, SubLevelEmbedding> EMBEDDINGS = new WeakHashMap<>();
    /** Block entities Flywheel must not visualize now. Read from any thread (Flywheel queues anywhere). */
    private static final Set<BlockEntity> VETOED = ConcurrentHashMap.newKeySet();
    /** Given back and not yet found in Flywheel's record: their renderers keep drawing them. */
    private static final Set<BlockEntity> RETURNING = Collections.newSetFromMap(new WeakHashMap<>());
    /** Handed block entities per body of the current level (for its body passes). */
    private static final Map<ClientSubLevel, List<BlockEntity>> BY_BODY = new WeakHashMap<>();
    /** When each handed block entity was last wanted by its renderer (render thread only). */
    private static final Map<BlockEntity, Long> LAST_WANTED = new WeakHashMap<>();
    /** Contraption entities handed to Create's renderer, with the same life cycle as block entities. */
    private static final Set<Entity> VETOED_ENTITIES = ConcurrentHashMap.newKeySet();
    private static final Set<Entity> RETURNING_ENTITIES = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<Entity, Long> ENTITY_LAST_WANTED = new WeakHashMap<>();
    /**
     * Contraptions refused while their structure had not arrived. A contraption entering a level
     * other than the player's gets its structure after it joins: nothing queued it again, so it
     * stayed invisible until its level was reloaded.
     */
    private static final Set<Entity> AWAITING_STRUCTURE = ConcurrentHashMap.newKeySet();

    /** Whether Flywheel must refuse {@code be} now ({@code FlywheelStorageVetoMixin}). */
    public static boolean isVetoed(BlockEntity be) {
        return VETOED.contains(be);
    }

    /** Whether Flywheel must refuse the visual of contraption {@code entity} now. */
    public static boolean isVetoed(Entity entity) {
        return VETOED_ENTITIES.contains(entity);
    }

    /** Whether Flywheel must refuse contraption {@code entity} now: handed to its renderer, or without its structure yet. */
    public static boolean refuses(Entity entity) {
        if (isVetoed(entity)) return true;
        if (!IplContraptions.isHollow(entity)) return false;
        AWAITING_STRUCTURE.add(entity);
        return true;
    }

    /** Whether Create's renderer draws contraption {@code entity} this frame instead of Flywheel. */
    public static boolean isHanded(Entity entity) {
        return VETOED_ENTITIES.contains(entity) || RETURNING_ENTITIES.contains(entity);
    }

    /** HEAD {@code EnvironmentStorage.flush()}: the current level's Flywheel draws a frame (its plan is synced). */
    public static void beginEnvironmentFlush() {
        HIDDEN.clear();
        ClientLevel level = Minecraft.getInstance().level;
        VisualizationManagerImpl manager = VisualizationManagerImpl.get(level);
        if (manager == null) return;
        VisualManager<BlockEntity> visuals = manager.blockEntities();
        BlockEntityStorage storage = storage(visuals);
        Set<BlockEntity> wanted = Collections.newSetFromMap(new IdentityHashMap<>());
        collectBodies(level, storage, wanted);
        for (BlockEntity be : IplPortalModels.candidates(level)) {
            if (Sable.HELPER.getContainingClient(be) == null && IplPortalModels.isNearOpening(be)) wanted.add(be);
        }
        reconcile(level, visuals, record(storage), wanted);
        reconcileEntities(level, manager.entities(), IplContraptionRender.wantedByRenderer(level));
    }

    /** {@link #reconcile} for contraption entities. */
    @SuppressWarnings("unchecked")
    private static void reconcileEntities(ClientLevel level, VisualManager<Entity> visuals, Set<Entity> wanted) {
        Map<Object, ?> record = ((FlywheelStorageAccessor) (Object)
            ((VisualManagerImpl<Entity, ?>) visuals).getStorage()).ipl$visuals();
        long now = System.nanoTime();
        for (Entity entity : wanted) {
            ENTITY_LAST_WANTED.put(entity, now);
            RETURNING_ENTITIES.remove(entity);
            if (VETOED_ENTITIES.add(entity) && record.containsKey(entity)) visuals.queueRemove(entity);
        }
        for (Entity entity : new ArrayList<>(VETOED_ENTITIES)) {
            if (entity.isRemoved()) {
                VETOED_ENTITIES.remove(entity);
                continue;
            }
            if (entity.level() != level || wanted.contains(entity)) continue;
            Long last = ENTITY_LAST_WANTED.get(entity);
            if (last != null && now - last < RELEASE_NANOS) continue;
            VETOED_ENTITIES.remove(entity);
            ENTITY_LAST_WANTED.remove(entity);
            RETURNING_ENTITIES.add(entity);
        }
        if (!AWAITING_STRUCTURE.isEmpty()) {
            for (Entity entity : AWAITING_STRUCTURE) {
                if (entity.isRemoved()) AWAITING_STRUCTURE.remove(entity);
                else if (entity.level() == level && !IplContraptions.isHollow(entity)) {
                    AWAITING_STRUCTURE.remove(entity);
                    if (!VETOED_ENTITIES.contains(entity)) RETURNING_ENTITIES.add(entity);
                }
            }
        }
        RETURNING_ENTITIES.removeIf(entity -> entity.isRemoved() || IplContraptions.isHollow(entity)
            || entity.level() == level && record.containsKey(entity));
        for (Entity entity : RETURNING_ENTITIES) if (entity.level() == level) visuals.queueAdd(entity);
        IplContraptionDiagnostics.report(level, record::containsKey);
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

    /**
     * Vetoes what is wanted (removing its visual), gives back the rest of this level's vetoed
     * block entities, and keeps re-queuing given-back ones until Flywheel's record has them.
     */
    private static void reconcile(ClientLevel level, VisualManager<BlockEntity> visuals, Map<Object, ?> record,
                                  Set<BlockEntity> wanted) {
        long now = System.nanoTime();
        for (BlockEntity be : wanted) {
            LAST_WANTED.put(be, now);
            RETURNING.remove(be);
            if (!VETOED.add(be)) continue;
            if (record.containsKey(be)) visuals.queueRemove(be);
            IplPortalModelDiagnostics.handOver(be, true);
        }
        for (BlockEntity be : new ArrayList<>(VETOED)) {
            if (be.getLevel() != level || wanted.contains(be)) continue;
            Long last = LAST_WANTED.get(be);
            if (!be.isRemoved() && last != null && now - last < RELEASE_NANOS) continue;
            VETOED.remove(be);
            LAST_WANTED.remove(be);
            if (be.isRemoved()) continue;
            RETURNING.add(be);
            IplPortalModels.notice(be);
            IplPortalModelDiagnostics.handOver(be, false);
        }
        RETURNING.removeIf(be -> be.isRemoved() || be.getLevel() == level && record.containsKey(be));
        for (BlockEntity be : RETURNING) if (be.getLevel() == level) visuals.queueAdd(be);
        indexBodies(level);
    }

    private static void indexBodies(ClientLevel level) {
        BY_BODY.clear();
        for (Set<BlockEntity> set : List.of(VETOED, RETURNING)) {
            for (BlockEntity be : set) {
                if (be.getLevel() != level) continue;
                ClientSubLevel sub = Sable.HELPER.getContainingClient(be);
                if (sub != null) BY_BODY.computeIfAbsent(sub, s -> new ArrayList<>()).add(be);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static BlockEntityStorage storage(VisualManager<BlockEntity> visuals) {
        return ((VisualManagerImpl<BlockEntity, BlockEntityStorage>) visuals).getStorage();
    }

    private static Map<Object, ?> record(BlockEntityStorage storage) {
        return ((FlywheelStorageAccessor) (Object) storage).ipl$visuals();
    }

    public static boolean isHiddenEnvironment(Object environment) {
        return HIDDEN.contains(environment);
    }

    /** Whether the renderer of {@code be} draws it this frame instead of Flywheel. */
    public static boolean isHanded(BlockEntity be) {
        return VETOED.contains(be) || RETURNING.contains(be);
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
}
