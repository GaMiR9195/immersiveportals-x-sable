package ipl.sable.render;

import dev.engine_room.flywheel.api.visualization.VisualManager;
import dev.engine_room.flywheel.impl.visualization.VisualManagerImpl;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.neoforge.mixinhelper.compatibility.flywheel.SubLevelEmbedding;
import dev.ryanhcode.sable.neoforge.mixinterface.compatibility.flywheel.BlockEntityStorageExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplParentDimSync;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Flywheel visuals (Create cogs, shafts...) of sub-levels that cross portals.
 *
 * <p>Flywheel draws a sub-level's visuals in one embedded environment at the REAL pose and only
 * in its own level's pass: it cannot cut them at an aperture nor draw them at a projection's
 * mapped pose. So, per visual, while a body has session images: a block entity that is not
 * wholly in the native piece (another piece's label, or near an aperture) is taken out of
 * Flywheel ({@code queueRemove}) and drawn by its BER in the piece passes of
 * {@link IplSubLevelBlockEntityPass}; it goes back ({@code queueAdd}) once it is wholly native
 * again or the sessions end. Everything else keeps instancing. Removal and addition apply on
 * Flywheel's next frame plan, so a taken block entity is drawn by BER from the moment Flywheel
 * no longer has it until the moment Flywheel has it again: never by both, never by none.
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

    private static final class Taken {
        /** Removal queued: BER draws them once Flywheel has dropped them. */
        final Set<BlockEntity> out = identitySet();
        /** Addition queued: BER keeps drawing them until Flywheel has them again. */
        final Set<BlockEntity> back = identitySet();
        /** What BER draws this frame: taken and not visualized. */
        List<BlockEntity> draw = List.of();
    }

    private static <T> Set<T> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /** HEAD {@code EnvironmentStorage.flush()}: the current level's Flywheel draws a frame. */
    public static void beginEnvironmentFlush() {
        HIDDEN.clear();
        ClientLevel level = Minecraft.getInstance().level;
        VisualizationManagerImpl manager = VisualizationManagerImpl.get(level);
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (manager == null || container == null) return;
        VisualManager<BlockEntity> visuals = manager.blockEntities();
        // Flywheel's BE storage carries Sable's extension (its compat mixin), as in Simulated.
        BlockEntityStorageExtension storage =
            (BlockEntityStorageExtension) ((VisualManagerImpl<?, ?>) visuals).getStorage();
        for (ClientSubLevel sub : container.getAllSubLevels()) {
            SubLevelEmbedding info = storage.sable$getEmbeddingInfo(sub);
            if (info == null) EMBEDDINGS.remove(sub);
            else EMBEDDINGS.put(sub, info);
            if (IplParentDimSync.isSuppressedSuccessor(sub)) {
                if (info != null) HIDDEN.add(info.embedding());
                continue;
            }
            IplPieceRender.Frame frame = IplPieceRender.frameFor(sub);
            Taken taken = TAKEN.get(sub);
            if (frame == null && taken == null) continue;
            if (taken == null) TAKEN.put(sub, taken = new Taken());
            update(visuals, frame, info, taken);
            if (frame == null && taken.out.isEmpty() && taken.back.isEmpty()) TAKEN.remove(sub);
        }
    }

    private static void update(VisualManager<BlockEntity> visuals, IplPieceRender.Frame frame,
                               SubLevelEmbedding info, Taken taken) {
        Set<BlockEntity> visualized = identitySet();
        if (info != null) visualized.addAll(info.blockEntities());
        if (frame != null) {
            for (BlockEntity be : visualized) {
                if (IplPieceRender.flywheelKeeps(frame, be.getBlockPos())) continue;
                visuals.queueRemove(be);
                taken.out.add(be);
                taken.back.remove(be);
            }
        }
        for (Iterator<BlockEntity> it = taken.out.iterator(); it.hasNext(); ) {
            BlockEntity be = it.next();
            if (be.isRemoved()) {
                it.remove();
            } else if (frame == null || IplPieceRender.flywheelKeeps(frame, be.getBlockPos())) {
                visuals.queueAdd(be);
                it.remove();
                taken.back.add(be);
            }
        }
        // Not accepted again (Flywheel refused it) or already back: nothing left to cover.
        taken.back.removeIf(be -> be.isRemoved() || visualized.contains(be));
        List<BlockEntity> draw = new ArrayList<>(taken.out.size() + taken.back.size());
        for (BlockEntity be : taken.out) if (!visualized.contains(be)) draw.add(be);
        draw.addAll(taken.back);
        taken.draw = draw;
    }

    public static boolean isHiddenEnvironment(Object environment) {
        return HIDDEN.contains(environment);
    }

    /** Block entities of {@code sub} whose Flywheel visual is not drawn and that BER must draw. */
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
