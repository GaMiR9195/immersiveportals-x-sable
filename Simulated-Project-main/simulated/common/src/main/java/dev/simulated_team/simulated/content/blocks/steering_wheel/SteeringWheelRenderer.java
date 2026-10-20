package dev.simulated_team.simulated.content.blocks.steering_wheel;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelRenderer;
import com.simibubi.create.foundation.model.BakedModelHelper;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.simulated_team.simulated.index.SimPartialModels;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.createmod.catnip.registry.RegisteredObjectsHelper;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperBufferFactory;
import net.createmod.catnip.render.SuperByteBuffer;
import net.createmod.catnip.render.SuperByteBufferCache;
import net.createmod.catnip.theme.Color;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

public class SteeringWheelRenderer extends KineticBlockEntityRenderer<SteeringWheelBlockEntity> {
    public static final SuperByteBufferCache.Compartment<ModelKey> STEERING_WHEEL = new SuperByteBufferCache.Compartment<>();


    public SteeringWheelRenderer(final BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(final SteeringWheelBlockEntity be, final float partialTicks, final PoseStack ms, final MultiBufferSource buffer,
                              final int light, final int overlay) {
        if (VisualizationManager.supportsVisualization(be.getLevel())) {
            return;
        }

        final boolean floor = be.getBlockState().getValue(SteeringWheelBlock.ON_FLOOR);
        final Direction facing = be.getBlockState().getValue(SteeringWheelBlock.FACING);

        if (be.shouldRenderShaft()) {
            final BlockState state = this.getRenderedBlockState(be);
            final RenderType type = this.getRenderType(be, state);
            renderRotatingBuffer(be, CachedBuffers.partialFacing(
                    AllPartialModels.SHAFT_HALF,
                    be.getBlockState(),
                    floor ? Direction.DOWN : Direction.UP
            ), ms, buffer.getBuffer(type), light);
        }

        final SuperByteBuffer model = this.getWheelModel(be);

        model.rotateCentered(facing.getRotation());
        if (floor) {
            model.translate(0, 6.5 / 16f, -5 / 16f);
        } else {
            model.translate(0, 6.5 / 16f, 5 / 16f);
        }
        model.rotateCentered(be.getRenderAngle(partialTicks), Direction.UP);

        model.light(light);
        model.color(Color.WHITE);
        model.renderInto(ms, buffer.getBuffer(RenderType.solid()));
    }

    private SuperByteBuffer getWheelModel(final SteeringWheelBlockEntity be) {
        final ModelKey key = new ModelKey(be.material);
        return SuperByteBufferCache.getInstance().get(STEERING_WHEEL, key, () -> {
            final BakedModel model = generateModel(SimPartialModels.STEERING_WHEEL.get(), be.material);
            return SuperBufferFactory.getInstance().createForBlock(model, Blocks.AIR.defaultBlockState(), new PoseStack());
        });
    }

    public static BakedModel generateModel(final BakedModel template, final BlockState planksBlockState) {
        final Block planksBlock = planksBlockState.getBlock();
        final ResourceLocation id = RegisteredObjectsHelper.getKeyOrThrow(planksBlock);
        final String wood = plankStateToWoodName(planksBlockState);

        if (wood == null)
            return BakedModelHelper.generateModel(template, sprite -> null);

        final Map<TextureAtlasSprite, TextureAtlasSprite> map = new Reference2ReferenceOpenHashMap<>();
        map.put(WaterWheelRenderer.OAK_PLANKS_TEMPLATE.get(), getSpriteOnSide(planksBlockState, Direction.UP));

        return BakedModelHelper.generateModel(template, map::get);
    }

    public record ModelKey(BlockState material) {
    }

    // todo tell create to make these public :p
    @Nullable
    private static String plankStateToWoodName(final BlockState planksBlockState) {
        final Block planksBlock = planksBlockState.getBlock();
        final ResourceLocation id = RegisteredObjectsHelper.getKeyOrThrow(planksBlock);
        final String path = id.getPath();

        if (path.endsWith("_planks")) // Covers most wood types
            return (path.startsWith("archwood") ? "blue_" : "") + path.substring(0, path.length() - 7);

        if (path.contains("wood/planks/")) // TerraFirmaCraft
            return path.substring(12);

        return null;
    }

    private static TextureAtlasSprite getSpriteOnSide(final BlockState state, final Direction side) {
        final BakedModel model = Minecraft.getInstance()
                .getBlockRenderer()
                .getBlockModel(state);
        if (model == null)
            return null;
        final RandomSource random = RandomSource.create();
        random.setSeed(42L);
        List<BakedQuad> quads = model.getQuads(state, side, random);
        if (!quads.isEmpty()) {
            return quads.get(0)
                    .getSprite();
        }
        random.setSeed(42L);
        quads = model.getQuads(state, null, random);
        if (!quads.isEmpty()) {
            for (final BakedQuad quad : quads) {
                if (quad.getDirection() == side) {
                    return quad.getSprite();
                }
            }
        }
        return model.getParticleIcon();
    }
}
