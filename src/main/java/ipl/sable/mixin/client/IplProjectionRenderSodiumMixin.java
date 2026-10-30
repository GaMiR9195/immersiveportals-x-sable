package ipl.sable.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher;
import foundry.veil.api.client.render.VeilRenderBridge;
import foundry.veil.api.client.render.rendertype.VeilRenderType;
import ipl.sable.client.IplClientProjections;
import ipl.sable.client.IplProjectionRenderSequence;
import ipl.sable.client.IplStraddleRenderState;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PrioritizeChunkUpdates;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEWorldRenderer;

import java.util.List;
import java.util.Objects;

/**
 * Sodium-backend parity for {@link IplProjectionRenderMixin}. With Sodium loaded,
 * {@code LevelRenderer}'s vanilla terrain path (setupRender / compileSections /
 * renderSectionLayer) is dormant and Sable drives sub-level rendering from
 * {@code SodiumWorldRenderer}. This mixin mirrors Sable's Sodium hook points for the straddle
 * projections drawn into the current pass. The vanilla mixin's {@code renderLevel} hooks
 * (render cache, block entities) still fire under Sodium and are not duplicated here.
 *
 * <p>Priority 1010: applies after Sable's own render mixins (1002).
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", priority = 1010, remap = false)
public abstract class IplProjectionRenderSodiumMixin {

    @Shadow
    private ClientLevel level;

    /**
     * The dimension THIS pass is rendering: IP swaps {@code Minecraft.level} around each
     * portal-content render; fall back to the renderer's own level outside a frame.
     */
    @Unique
    private ClientLevel ipl$passLevel() {
        ClientLevel passLevel = Minecraft.getInstance().level;
        return passLevel != null ? passLevel : this.level;
    }

    @Unique
    private List<IplClientProjections.StraddleProjection> ipl$projections() {
        return IplClientProjections.getStraddleProjectionsInto(ipl$passLevel());
    }

    // Sable: sable$markGraphDirty
    @Inject(
        method = "setupTerrain",
        at = @At(
            value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;markGraphDirty()V"
        )
    )
    private void ipl$cullProjections(
        Camera camera, Viewport viewport, boolean spectator, boolean updateChunksImmediately,
        CallbackInfo ci
    ) {
        List<IplClientProjections.StraddleProjection> projections = ipl$projections();
        if (projections.isEmpty()) return;
        Frustum frustum = ((IEWorldRenderer) Minecraft.getInstance().levelRenderer).portal_getFrustum();
        if (frustum == null) return;
        Vec3 cameraPosition = camera.getPosition();
        SubLevelRenderDispatcher dispatcher = SubLevelRenderDispatcher.get();
        for (IplClientProjections.StraddleProjection projection : projections) {
            IplStraddleRenderState.set(
                projection.sub(), projection.mappedPose(), projection.destPlane(), projection.portal());
            try {
                dispatcher.updateCulling(
                    List.of(projection.sub()), cameraPosition.x, cameraPosition.y, cameraPosition.z,
                    VeilRenderBridge.create(frustum), spectator
                );
            } finally {
                IplStraddleRenderState.clear();
            }
        }
    }

    // Sable: sable$setupTerrain
    @Inject(method = "setupTerrain", at = @At("TAIL"))
    private void ipl$compileProjectionSections(
        Camera camera, Viewport viewport, boolean spectator, boolean updateChunksImmediately,
        CallbackInfo ci
    ) {
        List<IplClientProjections.StraddleProjection> projections = ipl$projections();
        if (projections.isEmpty()) return;
        RenderRegionCache renderRegionCache = new RenderRegionCache();
        PrioritizeChunkUpdates chunkUpdates =
            SodiumClientMod.options().performance.chunkBuildDeferMode.getImportantRebuildQueueType()
                == TaskQueueType.ALWAYS_DEFER
                ? PrioritizeChunkUpdates.NONE
                : PrioritizeChunkUpdates.NEARBY;
        for (IplClientProjections.StraddleProjection projection : projections) {
            projection.sub().getRenderData().compileSections(chunkUpdates, renderRegionCache, camera);
        }
    }

    // Sable: sable$drawRenderSources (+ Veil layered types)
    @Inject(method = "drawChunkLayer", at = @At("TAIL"))
    private void ipl$renderProjectionSectionLayer(
        RenderType renderType, ChunkRenderMatrices matrices, double x, double y, double z,
        CallbackInfo ci
    ) {
        List<IplClientProjections.StraddleProjection> projections = ipl$projections();
        if (projections.isEmpty()) return;

        Minecraft minecraft = Minecraft.getInstance();
        float partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(false);
        Matrix4f modelView = new Matrix4f(matrices.modelView());
        Matrix4f projection = new Matrix4f(matrices.projection());

        // Sub-level geometry draws through the vanilla ShaderInstance pipeline even under
        // Sodium (reach-around dispatcher), so set up vanilla render state around it.
        ipl$drawLayer(renderType, projections, x, y, z, modelView, projection, partialTick);

        RenderType unwrapped = renderType;
        while (unwrapped instanceof VeilRenderType.RenderTypeWrapper wrapper) {
            unwrapped = wrapper.get();
        }
        if (!(unwrapped instanceof VeilRenderType.LayeredRenderType layered)) return;
        for (RenderType layer : layered.getLayers()) {
            ipl$drawLayer(layer, projections, x, y, z, modelView, projection, partialTick);
        }
    }

    @Unique
    private void ipl$drawLayer(
        RenderType layer, List<IplClientProjections.StraddleProjection> projections,
        double x, double y, double z, Matrix4f modelView, Matrix4f projection, float partialTick
    ) {
        SubLevelRenderDispatcher dispatcher = SubLevelRenderDispatcher.get();
        layer.setupRenderState();
        ShaderInstance shader = null;
        try {
            shader = Objects.requireNonNull(RenderSystem.getShader(), "shader");
            shader.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection,
                Minecraft.getInstance().getWindow());
            shader.apply();
            for (IplClientProjections.StraddleProjection proj : projections) {
                IplStraddleRenderState.set(proj.sub(), proj.mappedPose(), proj.destPlane(), proj.portal());
                try {
                    ipl$updateCulling(List.of(proj.sub()));
                    dispatcher.renderSectionLayer(
                        List.of(proj.sub()), layer, shader, x, y, z, modelView, projection, partialTick);
                } finally {
                    IplStraddleRenderState.clear();
                }
            }
        } finally {
            if (shader != null) shader.clear();
            layer.clearRenderState();
        }
    }

    /**
     * Sable queues one-block sub-level layers during {@code renderSectionLayer} and drains that
     * queue only from {@code renderAfterSections}; under Sodium the drain follows the matching
     * chunk layer. ONE drain over all projections (the queue is shared and cleared).
     * x/y/z are THIS pass's camera origin (the main camera is wrong inside a portal pass).
     */
    @Inject(method = "drawChunkLayer", at = @At("TAIL"))
    private void ipl$renderProjectionSingleBlocks(
        RenderType renderType, ChunkRenderMatrices matrices, double x, double y, double z,
        CallbackInfo ci
    ) {
        List<IplClientProjections.StraddleProjection> projections = ipl$projections();
        if (projections.isEmpty()) return;
        float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        IplProjectionRenderSequence sequence = new IplProjectionRenderSequence(projections);
        try {
            SubLevelRenderDispatcher.get().renderAfterSections(
                sequence, x, y, z, new Matrix4f(matrices.modelView()), new Matrix4f(matrices.projection()),
                partialTick);
        } finally {
            sequence.disarm();
        }
    }

    @Unique
    private void ipl$updateCulling(List<ClientSubLevel> sublevels) {
        Frustum frustum = ((IEWorldRenderer) Minecraft.getInstance().levelRenderer).portal_getFrustum();
        if (frustum == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 cameraPosition = minecraft.gameRenderer.getMainCamera().getPosition();
        boolean spectator = minecraft.player != null && minecraft.player.isSpectator();
        SubLevelRenderDispatcher.get().updateCulling(
            sublevels, cameraPosition.x, cameraPosition.y, cameraPosition.z,
            VeilRenderBridge.create(frustum), spectator
        );
    }
}
