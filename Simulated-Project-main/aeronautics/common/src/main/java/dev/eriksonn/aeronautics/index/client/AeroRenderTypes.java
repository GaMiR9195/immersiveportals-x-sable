package dev.eriksonn.aeronautics.index.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.eriksonn.aeronautics.Aeronautics;
import dev.eriksonn.aeronautics.content.blocks.levitite.LevititeShaderManager;
import foundry.veil.api.client.render.VeilRenderBridge;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL30C.GL_RASTERIZER_DISCARD;

public class AeroRenderTypes extends RenderType {

    public static final ResourceLocation LEVITITE_SHADER = Aeronautics.path("levitite/levitite");
    private static final ShaderStateShard LEVITITE_SHADER_SHARD = new LevititeShaderState(VeilRenderBridge.shaderState(LEVITITE_SHADER), new OutputStateShard("disabled", () -> {
        RENDERTYPE_SOLID_SHADER.setupRenderState();
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.depthMask(false);
    }, () -> {
        RENDERTYPE_SOLID_SHADER.clearRenderState();
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
    }));

    private static final RenderType LEVITITE = RenderType.create(
            Aeronautics.MOD_ID + ":levitite",
            DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS,
            TRANSIENT_BUFFER_SIZE,
            false,
            true,
            VeilRenderBridge.create(
                            RenderType.CompositeState.builder()
                                    .setShaderState(LEVITITE_SHADER_SHARD)
                                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                                    .setCullState(CULL)
                                    .setTextureState(RenderStateShard.BLOCK_SHEET)
                                    .setLightmapState(LightmapStateShard.LIGHTMAP)
                    )
                    .addLayer(VeilRenderBridge.patchState(4))
                    .create(false)
    );

    private static final RenderType LEVITITE_GHOSTS = RenderType.create(
            Aeronautics.MOD_ID + ":levitite_ghosts",
            DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS,
            TRANSIENT_BUFFER_SIZE,
            false,
            true,
            VeilRenderBridge.create(
                            RenderType.CompositeState.builder()
                                    .setShaderState(LEVITITE_SHADER_SHARD)
                                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                                    .setCullState(NO_CULL)
                                    .setTextureState(RenderStateShard.BLOCK_SHEET)
                                    .setLightmapState(LightmapStateShard.LIGHTMAP)
                    )
                    .addLayer(VeilRenderBridge.patchState(4))
                    .create(false)
    );

    public AeroRenderTypes(final String name,
                           final VertexFormat format,
                           final VertexFormat.Mode mode,
                           final int bufferSize,
                           final boolean affectsCrumbling,
                           final boolean sortOnUpload,
                           final Runnable setupState,
                           final Runnable clearState) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
    }

    public static RenderType levitite() {
        return LEVITITE;
    }

    public static RenderType levititeGhosts() {
        return LEVITITE_GHOSTS;
    }

    private static class LevititeShaderState extends RenderStateShard.ShaderStateShard {

        private final RenderStateShard enabled;
        private final RenderStateShard disabled;

        public LevititeShaderState(RenderStateShard enabled, RenderStateShard disabled) {
            this.enabled = enabled;
            this.disabled = disabled;
        }

        @Override
        public void setupRenderState() {
            if (LevititeShaderManager.isEnabled()) {
                this.enabled.setupRenderState();
            } else {
                this.disabled.setupRenderState();
            }
        }

        @Override
        public void clearRenderState() {
            if (LevititeShaderManager.isEnabled()) {
                this.enabled.clearRenderState();
            } else {
                this.disabled.clearRenderState();
            }
        }

        @Override
        public String toString() {
            return LevititeShaderManager.isEnabled() ? this.enabled.toString() : this.disabled.toString();
        }
    }
}
