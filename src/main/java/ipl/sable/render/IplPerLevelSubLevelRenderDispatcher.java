package ipl.sable.render;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.SubLevelRenderData;
import dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaSingleSubLevelRenderData;
import ipl.sable.client.IplClientProjections;
import ipl.sable.mixin.client.IplSectionRenderDispatcherAccessor;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.SectionBufferBuilderPool;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import qouteall.imm_ptl.core.ClientWorldLoader;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Sodium-path sub-level renderer with one section compile pipeline per client level.
 *
 * <p>Sable's reach-around renderer builds the sections of every sub-level through ONE vanilla
 * {@link SectionRenderDispatcher}, re-pointed with {@code setLevel} whenever render data is
 * created. A compile task reads blocks, light and block entities from its dispatcher's level
 * when it runs, so with several client levels alive at once (Immersive Portals) a ship in
 * another dimension was compiled against whichever level created render data last: wrong
 * light, missing or foreign blocks, until something re-dirtied the sections.
 *
 * <p>Here every level gets a dispatcher bound to it (and to its own {@link LevelRenderer}) for
 * life, plus a private buffer pool: {@link SectionBufferBuilderPool} is not thread-safe, so two
 * dispatchers must never share one (Immersive Portals does the same for terrain). Drawing is
 * Sable's vanilla sub-level rendering, inherited unchanged.
 */
public final class IplPerLevelSubLevelRenderDispatcher extends VanillaSubLevelRenderDispatcher {

    private final Map<ClientLevel, Pipeline> pipelines = new IdentityHashMap<>();

    /** Disposed pipelines whose packs may still be in use by compile tasks finishing off-thread. */
    private final List<Pipeline> retired = new ArrayList<>();

    @Override
    public SubLevelRenderData createRenderData(ClientSubLevel subLevel) {
        if (isSingleBlock(subLevel)) {
            return new VanillaSingleSubLevelRenderData(subLevel);
        }
        Pipeline pipeline = pipelines.computeIfAbsent(subLevel.getLevel(), Pipeline::create);
        return new VanillaChunkedSubLevelRenderData(subLevel, pipeline.dispatcher());
    }

    @Override
    public void rebuild(Iterable<ClientSubLevel> sublevels) {
        // Only the rebuilt levels drop their queued compiles; other levels keep theirs.
        for (ClientSubLevel subLevel : sublevels) {
            Pipeline pipeline = pipelines.get(subLevel.getLevel());
            if (pipeline != null) pipeline.dispatcher().blockUntilClear();
        }
        super.rebuild(sublevels);
    }

    /** Called from each level's terrain setup, with that level made current by Immersive Portals. */
    @Override
    public void preRenderChunks(Camera camera) {
        Minecraft minecraft = Minecraft.getInstance();
        retireUnloadedLevels(minecraft);

        Pipeline current = pipelines.get(minecraft.level);
        if (current != null) current.dispatcher().setCamera(camera.getPosition());

        // Uploads of every level: a level seen through no portal this frame still finishes
        // its pending compiles, so it is ready when it comes into view.
        minecraft.getProfiler().push("sub_level_upload");
        for (Pipeline pipeline : pipelines.values()) {
            pipeline.dispatcher().uploadAllPendingUploads();
        }
        minecraft.getProfiler().pop();
    }

    @Override
    public void free() {
        pipelines.values().forEach(this::retire);
        pipelines.clear();
        freeIdlePacks();
        super.free();
    }

    private void retireUnloadedLevels(Minecraft minecraft) {
        for (Iterator<Map.Entry<ClientLevel, Pipeline>> it = pipelines.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<ClientLevel, Pipeline> entry = it.next();
            if (isLoaded(entry.getKey(), minecraft)) continue;
            retire(entry.getValue());
            it.remove();
        }
        freeIdlePacks();
    }

    private static boolean isLoaded(ClientLevel level, Minecraft minecraft) {
        if (level == minecraft.level) return true;
        for (ClientLevel loaded : IplClientProjections.clientLevels()) {
            if (loaded == level) return true;
        }
        return false;
    }

    private void retire(Pipeline pipeline) {
        pipeline.dispatcher().dispose();
        retired.add(pipeline);
    }

    private void freeIdlePacks() {
        retired.removeIf(pipeline -> {
            if (!pipeline.idle()) return false;
            pipeline.freePacks();
            return true;
        });
    }

    /** The renderer showing this level; compiled sections report global block entities to it. */
    private static LevelRenderer rendererOf(ClientLevel level) {
        LevelRenderer renderer = ClientWorldLoader.getIsInitialized()
            ? ClientWorldLoader.WORLD_RENDERER_MAP.get(level.dimension())
            : null;
        return renderer != null ? renderer : Minecraft.getInstance().levelRenderer;
    }

    /** A level's compile pipeline: the dispatcher bound to it and the buffer packs only it uses. */
    private record Pipeline(SectionRenderDispatcher dispatcher, SectionBufferBuilderPool pool, int packs) {

        static Pipeline create(ClientLevel level) {
            Minecraft minecraft = Minecraft.getInstance();
            SectionRenderDispatcher dispatcher = new SectionRenderDispatcher(
                level, rendererOf(level), Util.backgroundExecutor(), minecraft.renderBuffers(),
                minecraft.getBlockRenderer(), minecraft.getBlockEntityRenderDispatcher()
            );
            // Same pack count as Sable's single dispatcher. The new dispatcher has no compile
            // task yet, so nothing holds a pack of the shared pool and the swap is race-free.
            SectionBufferBuilderPool pool =
                SectionBufferBuilderPool.allocate(Math.max(1, Runtime.getRuntime().availableProcessors() / 4));
            ((IplSectionRenderDispatcherAccessor) dispatcher).ipl$setBufferPool(pool);
            return new Pipeline(dispatcher, pool, pool.getFreeBufferCount());
        }

        /** Every pack is back: no compile task writes into one, and a disposed dispatcher starts none. */
        boolean idle() {
            return pool.getFreeBufferCount() == packs;
        }

        void freePacks() {
            for (SectionBufferBuilderPack pack; (pack = pool.acquire()) != null; ) {
                pack.close();
            }
        }
    }
}
