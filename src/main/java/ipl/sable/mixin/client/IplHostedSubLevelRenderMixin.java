package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.ryanhcode.sable.mixinhelpers.sublevel_render.vanilla.VanillaSubLevelBlockEntityRenderer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher;
import foundry.veil.api.client.render.VeilRenderBridge;
import foundry.veil.api.client.render.rendertype.VeilRenderType;
import ipl.sable.client.IplClientHostedLookup;
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

import java.util.List;
import java.util.Objects;
import java.util.SortedSet;

/**
 * Phase 4 of the dim-agnostic refactor: render hosted sub-levels in their PARENT dimension's
 * render passes.
 *
 * <p>Sable's render hooks gather sub-levels from the camera level's own container; hosted
 * sub-levels live in the {@code ipl_sable:sublevels} container and would never be gathered.
 * This mixin mirrors each of Sable's vanilla-backend hook points, feeding
 * {@link IplClientHostedLookup#getHostedSubLevelsFor} — the hosted sub-levels whose synced
 * {@code parentLevel} is THIS renderer's level. Every {@code ClientLevel} has its own
 * {@code LevelRenderer} (IP's ClientWorldLoader), so the main pass and each portal pass
 * automatically pick up exactly the airships that belong in the dimension being drawn, and
 * IP's slot-0 clip stack crops them at portal planes like any other geometry.
 *
 * <p>Section geometry is compiled by the hosting world renderer's
 * {@code SectionRenderDispatcher} (see {@code IplHostedRenderDataMixin}), i.e. block/light
 * data is read from the sublevels {@code ClientLevel} while the pose transform places the
 * result in parent-dim world space.
 *
 * <p>Priority 1010: applies after Sable's own render mixins (1002) and Flywheel's.
 * Vanilla backend only — the Sodium reach-around backend hooks SodiumWorldRenderer and is a
 * known follow-up.
 */
@Mixin(value = LevelRenderer.class, priority = 1010)
public abstract class IplHostedSubLevelRenderMixin {

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

    @Unique
    private VanillaSubLevelBlockEntityRenderer ipl$hostedBeRenderer;

    @Unique
    private List<ClientSubLevel> ipl$hosted() {
        return IplClientHostedLookup.getHostedSubLevelsFor(this.level);
    }

    @Unique
    private List<IplClientHostedLookup.StraddleProjection> ipl$projections() {
        return IplClientHostedLookup.getStraddleProjectionsInto(this.level);
    }

    @Inject(method = "allChanged", at = @At("TAIL"))
    private void ipl$rebuildHosted(CallbackInfo ci) {
        List<ClientSubLevel> hosted = ipl$hosted();
        if (!hosted.isEmpty()) {
            SubLevelRenderDispatcher.get().rebuild(hosted);
        }
    }

    @Inject(method = "compileSections", at = @At("TAIL"))
    private void ipl$compileHostedSections(Camera camera, CallbackInfo ci) {
        List<ClientSubLevel> hosted = ipl$hosted();
        List<IplClientHostedLookup.StraddleProjection> projections = ipl$projections();
        if (hosted.isEmpty() && projections.isEmpty()) return;

        RenderRegionCache renderRegionCache = new RenderRegionCache();
        PrioritizeChunkUpdates chunkUpdates = Minecraft.getInstance().options.prioritizeChunkUpdates().get();
        for (ClientSubLevel sublevel : hosted) {
            sublevel.getRenderData().compileSections(chunkUpdates, renderRegionCache, camera);
        }
        // Projections share the native render data; compiling here covers the case where
        // the parent dim's own pass isn't rendered this frame (e.g. player on the dest side
        // with no portal view back).
        for (IplClientHostedLookup.StraddleProjection projection : projections) {
            projection.sub().getRenderData().compileSections(chunkUpdates, renderRegionCache, camera);
        }
    }

    @Inject(
        method = "setupRender",
        at = @At(
            value = "INVOKE_STRING",
            target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
            args = "ldc=update"
        )
    )
    private void ipl$cullHosted(
        Camera camera, Frustum frustum, boolean hasCapturedFrustum, boolean isSpectator, CallbackInfo ci
    ) {
        if (hasCapturedFrustum) return;
        List<ClientSubLevel> hosted = ipl$hosted();
        if (hosted.isEmpty()) return;

        Vec3 cameraPosition = camera.getPosition();
        SubLevelRenderDispatcher.get().updateCulling(
            hosted, cameraPosition.x, cameraPosition.y, cameraPosition.z,
            VeilRenderBridge.create(frustum), isSpectator
        );
    }

    @Inject(
        method = "renderSectionLayer",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ShaderInstance;clear()V")
    )
    private void ipl$renderHostedSectionLayer(
        RenderType renderType, double x, double y, double z,
        Matrix4f modelView, Matrix4f projection, CallbackInfo ci,
        @Local ShaderInstance shader
    ) {
        float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);

        List<ClientSubLevel> hosted = ipl$hosted();
        if (!hosted.isEmpty()) {
            SubLevelRenderDispatcher.get().renderSectionLayer(
                hosted, renderType, shader, x, y, z, modelView, projection, partialTick);
        }

        for (IplClientHostedLookup.StraddleProjection proj : ipl$projections()) {
            ipl.sable.client.IplStraddleRenderState.set(
                proj.sub(), proj.mappedPose(), proj.destPlane(), proj.portal());
            try {
                SubLevelRenderDispatcher.get().renderSectionLayer(
                    List.of(proj.sub()), renderType, shader, x, y, z, modelView, projection, partialTick);
            } finally {
                ipl.sable.client.IplStraddleRenderState.clear();
            }
        }
    }

    @Inject(method = "renderSectionLayer", at = @At("TAIL"))
    private void ipl$renderHostedVeilLayers(
        RenderType renderType, double x, double y, double z,
        Matrix4f modelView, Matrix4f projection, CallbackInfo ci
    ) {
        RenderType unwrapped = renderType;
        while (unwrapped instanceof VeilRenderType.RenderTypeWrapper wrapper) {
            unwrapped = wrapper.get();
        }
        if (!(unwrapped instanceof VeilRenderType.LayeredRenderType layered)) return;

        List<ClientSubLevel> hosted = ipl$hosted();
        if (hosted.isEmpty()) return;

        SubLevelRenderDispatcher dispatcher = SubLevelRenderDispatcher.get();
        for (RenderType layer : layered.getLayers()) {
            layer.setupRenderState();
            ShaderInstance shader = Objects.requireNonNull(RenderSystem.getShader(), "shader");
            shader.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection,
                Minecraft.getInstance().getWindow());
            shader.apply();

            dispatcher.renderSectionLayer(hosted, renderType, shader, x, y, z, modelView, projection,
                Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false));

            shader.clear();
            layer.clearRenderState();
        }
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
    private void ipl$renderHostedBlockEntities(
        DeltaTracker deltaTracker, boolean bl, Camera camera, GameRenderer gameRenderer,
        LightTexture lightTexture, Matrix4f modelView, Matrix4f projection, CallbackInfo ci
    ) {
        List<ClientSubLevel> hosted = ipl$hosted();
        List<IplClientHostedLookup.StraddleProjection> projections = ipl$projections();
        if (hosted.isEmpty() && projections.isEmpty()) return;

        if (ipl$hostedBeRenderer == null) {
            ipl$hostedBeRenderer = new VanillaSubLevelBlockEntityRenderer(
                this.blockEntityRenderDispatcher, this.renderBuffers, this.destructionProgress);
        }
        Vec3 cameraPosition = camera.getPosition();
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);

        if (!hosted.isEmpty()) {
            SubLevelRenderDispatcher.get().renderBlockEntities(
                hosted, ipl$hostedBeRenderer,
                cameraPosition.x, cameraPosition.y, cameraPosition.z, partialTick);
        }

        for (IplClientHostedLookup.StraddleProjection proj : projections) {
            ipl.sable.client.IplStraddleRenderState.set(
                proj.sub(), proj.mappedPose(), proj.destPlane(), proj.portal());
            try {
                SubLevelRenderDispatcher.get().renderBlockEntities(
                    List.of(proj.sub()), ipl$hostedBeRenderer,
                    cameraPosition.x, cameraPosition.y, cameraPosition.z, partialTick);
            } finally {
                ipl.sable.client.IplStraddleRenderState.clear();
            }
        }
    }

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/DimensionSpecialEffects;constantAmbientLight()Z",
            ordinal = 0,
            shift = At.Shift.BEFORE
        )
    )
    private void ipl$renderHostedAfterSections(
        DeltaTracker deltaTracker, boolean bl, Camera camera, GameRenderer gameRenderer,
        LightTexture lightTexture, Matrix4f modelView, Matrix4f projection, CallbackInfo ci
    ) {
        List<ClientSubLevel> hosted = ipl$hosted();
        List<IplClientHostedLookup.StraddleProjection> projections = ipl$projections();
        if (hosted.isEmpty() && projections.isEmpty()) return;

        Vec3 cameraPosition = camera.getPosition();
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);

        if (!hosted.isEmpty()) {
            SubLevelRenderDispatcher.get().renderAfterSections(
                hosted, cameraPosition.x, cameraPosition.y, cameraPosition.z,
                modelView, projection, partialTick);
        }

        for (IplClientHostedLookup.StraddleProjection proj : projections) {
            ipl.sable.client.IplStraddleRenderState.set(
                proj.sub(), proj.mappedPose(), proj.destPlane(), proj.portal());
            try {
                SubLevelRenderDispatcher.get().renderAfterSections(
                    List.of(proj.sub()), cameraPosition.x, cameraPosition.y, cameraPosition.z,
                    modelView, projection, partialTick);
            } finally {
                ipl.sable.client.IplStraddleRenderState.clear();
            }
        }
    }
}
