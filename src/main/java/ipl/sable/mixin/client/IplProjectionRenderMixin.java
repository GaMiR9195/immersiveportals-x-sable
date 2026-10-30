package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.ryanhcode.sable.mixinhelpers.sublevel_render.vanilla.VanillaSubLevelBlockEntityRenderer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher;
import foundry.veil.api.client.render.VeilRenderBridge;
import foundry.veil.api.client.render.rendertype.VeilRenderType;
import ipl.sable.client.IplClientProjections;
import ipl.sable.client.IplProjectionLevelScope;
import ipl.sable.client.IplProjectionRenderSequence;
import ipl.sable.client.IplStraddleRenderState;
import ipl.sable.client.IplStraddleRenderCache;
import ipl.sable.render.IplSubLevelBlockEntityPass;
import ipl.sable.render.SubLevelBlockEntityRenderScope;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PrioritizeChunkUpdates;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEWorldRenderer;

import java.util.List;
import java.util.Objects;
import java.util.SortedSet;

/**
 * Draws straddle projections — the far-side image of a sub-level that crosses a portal — in the
 * DESTINATION dimension's render passes (vanilla backend).
 *
 * <p>Native sub-levels are rendered by Sable itself in their own level's passes. A projection
 * reuses the source sub-level's render data with the mapped pose armed through
 * {@link IplStraddleRenderState}, so the same compiled sections are drawn a second time, beyond
 * the destination portal plane. Every {@code ClientLevel} has its own {@code LevelRenderer}
 * (IP's ClientWorldLoader), so each pass picks up exactly the projections whose path ends in
 * the dimension being drawn; same-dimension portals project into the source's own pass.
 *
 * <p>Priority 1010: applies after Sable's own render mixins (1002) and Flywheel's.
 */
@Mixin(value = LevelRenderer.class, priority = 1010)
public abstract class IplProjectionRenderMixin {

    @Shadow
    private @Nullable ClientLevel level;

    @Shadow
    @Final
    private BlockEntityRenderDispatcher blockEntityRenderDispatcher;

    @Shadow
    @Final
    private RenderBuffers renderBuffers;

    @Shadow
    @Final
    private Long2ObjectMap<SortedSet<BlockDestructionProgress>> destructionProgress;

    /** BE renderer for projections; rebuilt when IP swaps this renderer's buffers. */
    @Unique
    private VanillaSubLevelBlockEntityRenderer ipl$projectionBeRenderer;

    /** Buffer set captured by {@link #ipl$projectionBeRenderer}'s constructor. */
    @Unique
    private RenderBuffers ipl$projectionBeRenderBuffers;

    @Unique
    private List<IplClientProjections.StraddleProjection> ipl$projections() {
        return IplClientProjections.getStraddleProjectionsInto(this.level);
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void ipl$beginStraddleRenderCache(
        DeltaTracker deltaTracker, boolean bl, Camera camera, GameRenderer gameRenderer,
        LightTexture lightTexture, Matrix4f modelView, Matrix4f projection, CallbackInfo ci
    ) {
        IplStraddleRenderCache.begin(this.level);
        // Свой набор «уже нарисованных глобальных BE» на каждый (в т.ч. вложенный) проход.
        IplSubLevelBlockEntityPass.beginLevelPass();
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void ipl$endStraddleRenderCache(
        DeltaTracker deltaTracker, boolean bl, Camera camera, GameRenderer gameRenderer,
        LightTexture lightTexture, Matrix4f modelView, Matrix4f projection, CallbackInfo ci
    ) {
        IplStraddleRenderCache.end(this.level);
        IplSubLevelBlockEntityPass.endLevelPass();
    }

    /**
     * Projections share the source's render data; compiling here covers the case where the
     * source dimension's own pass isn't rendered this frame (player on the destination side
     * with no portal view back).
     */
    @Inject(method = "compileSections", at = @At("TAIL"))
    private void ipl$compileProjectionSections(Camera camera, CallbackInfo ci) {
        List<IplClientProjections.StraddleProjection> projections = ipl$projections();
        if (projections.isEmpty()) return;
        RenderRegionCache renderRegionCache = new RenderRegionCache();
        PrioritizeChunkUpdates chunkUpdates = Minecraft.getInstance().options.prioritizeChunkUpdates().get();
        for (IplClientProjections.StraddleProjection projection : projections) {
            projection.sub().getRenderData().compileSections(chunkUpdates, renderRegionCache, camera);
        }
    }

    /**
     * Section visibility of a projection must be computed with its mapped pose armed; the
     * source pass's culling is not valid in the destination pass.
     */
    @Inject(
        method = "setupRender",
        at = @At(
            value = "INVOKE_STRING",
            target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
            args = "ldc=update"
        )
    )
    private void ipl$cullProjections(
        Camera camera, Frustum frustum, boolean hasCapturedFrustum, boolean isSpectator, CallbackInfo ci
    ) {
        if (hasCapturedFrustum) return;
        List<IplClientProjections.StraddleProjection> projections = ipl$projections();
        if (projections.isEmpty()) return;
        Vec3 cameraPosition = camera.getPosition();
        SubLevelRenderDispatcher dispatcher = SubLevelRenderDispatcher.get();
        for (IplClientProjections.StraddleProjection projection : projections) {
            IplStraddleRenderState.set(projection);
            try {
                dispatcher.updateCulling(
                    List.of(projection.sub()), cameraPosition.x, cameraPosition.y, cameraPosition.z,
                    VeilRenderBridge.create(frustum), isSpectator
                );
            } finally {
                IplStraddleRenderState.clear();
            }
        }
    }

    @Inject(
        method = "renderSectionLayer",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ShaderInstance;clear()V")
    )
    private void ipl$renderProjectionSectionLayer(
        RenderType renderType, double x, double y, double z,
        Matrix4f modelView, Matrix4f projection, CallbackInfo ci,
        @Local ShaderInstance shader
    ) {
        List<IplClientProjections.StraddleProjection> projections = ipl$projections();
        if (projections.isEmpty()) return;
        float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        for (IplClientProjections.StraddleProjection proj : projections) {
            IplStraddleRenderState.set(proj);
            try {
                ipl$updateCullingForDraw(List.of(proj.sub()));
                SubLevelRenderDispatcher.get().renderSectionLayer(
                    List.of(proj.sub()), renderType, shader, x, y, z, modelView, projection, partialTick);
            } finally {
                IplStraddleRenderState.clear();
            }
        }
    }

    @Inject(method = "renderSectionLayer", at = @At("TAIL"))
    private void ipl$renderProjectionVeilLayers(
        RenderType renderType, double x, double y, double z,
        Matrix4f modelView, Matrix4f projection, CallbackInfo ci
    ) {
        RenderType unwrapped = renderType;
        while (unwrapped instanceof VeilRenderType.RenderTypeWrapper wrapper) {
            unwrapped = wrapper.get();
        }
        if (!(unwrapped instanceof VeilRenderType.LayeredRenderType layered)) return;
        List<IplClientProjections.StraddleProjection> projections = ipl$projections();
        if (projections.isEmpty()) return;

        SubLevelRenderDispatcher dispatcher = SubLevelRenderDispatcher.get();
        float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        for (RenderType layer : layered.getLayers()) {
            layer.setupRenderState();
            ShaderInstance shader = Objects.requireNonNull(RenderSystem.getShader(), "shader");
            shader.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection,
                Minecraft.getInstance().getWindow());
            shader.apply();
            for (IplClientProjections.StraddleProjection proj : projections) {
                IplStraddleRenderState.set(proj);
                try {
                    ipl$updateCullingForDraw(List.of(proj.sub()));
                    dispatcher.renderSectionLayer(
                        List.of(proj.sub()), layer, shader, x, y, z, modelView, projection, partialTick);
                } finally {
                    IplStraddleRenderState.clear();
                }
            }
            shader.clear();
            layer.clearRenderState();
        }
    }

    @Unique
    private void ipl$updateCullingForDraw(List<ClientSubLevel> sublevels) {
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

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;globalBlockEntities:Ljava/util/Set;",
            shift = At.Shift.BEFORE,
            ordinal = 0
        )
    )
    private void ipl$renderProjectionBlockEntities(
        DeltaTracker deltaTracker, boolean bl, Camera camera, GameRenderer gameRenderer,
        LightTexture lightTexture, Matrix4f modelView, Matrix4f projection, CallbackInfo ci
    ) {
        List<IplClientProjections.StraddleProjection> projections = ipl$projections();
        if (projections.isEmpty()) return;

        // IP temporarily swaps this LevelRenderer's RenderBuffers while it renders a portal
        // world. VanillaSubLevelBlockEntityRenderer captures that object in its constructor,
        // so a renderer retained across a swap would write into the old portal buffer.
        if (ipl$projectionBeRenderer == null || ipl$projectionBeRenderBuffers != this.renderBuffers) {
            ipl$projectionBeRenderer = new VanillaSubLevelBlockEntityRenderer(
                this.blockEntityRenderDispatcher, this.renderBuffers, this.destructionProgress);
            ipl$projectionBeRenderBuffers = this.renderBuffers;
        }
        Vec3 cameraPosition = camera.getPosition();
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        for (IplClientProjections.StraddleProjection proj : projections) {
            IplStraddleRenderState.set(proj);
            try {
                // BER межизмеренческой проекции видят «свой» мир в Minecraft.level (пружины,
                // свет Sable) — см. IplProjectionLevelScope; сброс буфера уже вне подмены.
                SubLevelBlockEntityRenderScope.renderAndFlush(
                    proj.sub(), this.renderBuffers.bufferSource(), () ->
                        IplProjectionLevelScope.runInOwnerLevel(proj.sub(), () ->
                            SubLevelRenderDispatcher.get().renderBlockEntities(
                                List.of(proj.sub()), ipl$projectionBeRenderer,
                                cameraPosition.x, cameraPosition.y, cameraPosition.z, partialTick
                            ))
                );
            } finally {
                IplStraddleRenderState.clear();
            }
        }
    }

    /** ONE drain of Sable's shared single-block queue over all projections. */
    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/DimensionSpecialEffects;constantAmbientLight()Z",
            ordinal = 0,
            shift = At.Shift.BEFORE
        )
    )
    private void ipl$renderProjectionAfterSections(
        DeltaTracker deltaTracker, boolean bl, Camera camera, GameRenderer gameRenderer,
        LightTexture lightTexture, Matrix4f modelView, Matrix4f projection, CallbackInfo ci
    ) {
        List<IplClientProjections.StraddleProjection> projections = ipl$projections();
        if (projections.isEmpty()) return;
        Vec3 cameraPosition = camera.getPosition();
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        IplProjectionRenderSequence sequence = new IplProjectionRenderSequence(projections);
        try {
            SubLevelRenderDispatcher.get().renderAfterSections(
                sequence, cameraPosition.x, cameraPosition.y, cameraPosition.z,
                modelView, projection, partialTick);
        } finally {
            sequence.disarm();
        }
    }
}
