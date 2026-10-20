package dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.effect;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.Balloon;
import dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.graph.BalloonLayerData;
import dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.graph.BalloonLayerGraph;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.render.region.SimpleCulledRenderRegionBuilder;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.util.LevelAccelerator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3i;
import org.lwjgl.system.NativeResource;

import java.util.Iterator;
import java.util.List;

public class HeatedCulledRenderRegion implements NativeResource {
    private Balloon balloon;
    private boolean built = false;
    private VertexBuffer buffer;
    private Vec3 origin;
    private final LevelAccelerator accelerator;

    public HeatedCulledRenderRegion(final LevelAccelerator accelerator, final Balloon balloon) {
        this.accelerator = accelerator;
        this.balloon = balloon;
    }

    public void render(final Matrix4f modelView, final Matrix4f projectionMatrix) {
        if (!this.built) {
            this.build();
        }

        if (this.buffer == null) {
            return;
        }


        final ShaderInstance shader = RenderSystem.getShader();
        assert shader != null;

        final Minecraft client = Minecraft.getInstance();
        final ClientSubLevel subLevel = Sable.HELPER.getContainingClient(this.origin);

        Vec3 globalOrigin = this.origin;
        final Quaternionf globalOrientation = new Quaternionf();

        if (subLevel != null) {
            final Pose3dc renderPose = subLevel.renderPose();
            globalOrigin = renderPose.transformPosition(globalOrigin);
            globalOrientation.set(renderPose.orientation());
        }

        final Vec3 relativePos = globalOrigin.subtract(client.gameRenderer.getMainCamera().getPosition());

        final Matrix4f modelViewMatrix = new Matrix4f(modelView)
                .setTranslation(0.0f, 0.0f, 0.0f)
                .translate((float) relativePos.x, (float) relativePos.y, (float) relativePos.z)
                .rotate(globalOrientation);

        shader.setDefaultUniforms(VertexFormat.Mode.QUADS, modelViewMatrix, projectionMatrix, client.getWindow());
        shader.apply();

        this.buffer.bind();
        this.buffer.draw();

        VertexBuffer.unbind();
    }

    public void build() {
        final BoundingBox3ic bounds = this.balloon.getBounds();
        final Vector3i minBlock = new Vector3i(bounds.minX(), bounds.minY(), bounds.minZ());
        final Vector3i maxBlock = new Vector3i(bounds.maxX(), bounds.maxY(), bounds.maxZ());

        int gridSize = maxBlock.x() - minBlock.x() + 1;
        gridSize = Math.max(gridSize, maxBlock.y() - minBlock.y() + 1);
        gridSize = Math.max(gridSize, maxBlock.z() - minBlock.z() + 1);

        final BlockPos originBlock = new BlockPos(minBlock.x(), minBlock.y(), minBlock.z());
        this.origin = Vec3.atLowerCornerOf(originBlock);

        final SimpleCulledRenderRegionBuilder builder = this.createMeshBuilder(gridSize);

        final BalloonLayerGraph graph = this.balloon.getGraph();


        for (int y = graph.getMinY(); y <= graph.getMaxY(); y++) {
            final List<BalloonLayerData> layers = graph.getLayersAtY(y);

            for (final BalloonLayerData layer : layers) {
                final Iterator<BlockPos> layerBlocks = layer.blockIterator();

                while (layerBlocks.hasNext()) {
                    final BlockPos blockPos = layerBlocks.next();

                    builder.add(blockPos.getX() - originBlock.getX(),
                            blockPos.getY() - originBlock.getY(),
                            blockPos.getZ() - originBlock.getZ());
                }
            }
        }

        builder.buildNoGreedy();

        final BufferBuilder bufferBuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, this.getVertexFormat());
        builder.render(new Matrix4f(), bufferBuilder);

        this.balloon = null;
        final MeshData builtData = bufferBuilder.build();

        if (builtData != null) {
            this.buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            this.buffer.bind();
            this.buffer.upload(builtData);
        } else {
            this.buffer = null;
        }

        this.built = true;
    }

    public Vec3 getOrigin() {
        return this.origin;
    }

    public SimpleCulledRenderRegionBuilder createMeshBuilder(final int gridSize) {
        return new HeatedCulledRenderRegionBuilder(BlockPos.containing(this.getOrigin()), this.accelerator, gridSize);
    }

    public VertexFormat getVertexFormat() {
        return DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL;
    }

    @Override
    public void free() {
        if (this.built && this.buffer != null) {
            this.buffer.close();
        }
    }
}
