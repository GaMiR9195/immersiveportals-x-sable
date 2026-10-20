package dev.simulated_team.simulated.neoforge.service;

import com.tterrag.registrate.providers.DataGenContext;
import com.tterrag.registrate.providers.RegistrateBlockstateProvider;
import com.tterrag.registrate.util.nullness.NonNullBiConsumer;
import dev.simulated_team.simulated.content.blocks.auger_shaft.AugerShaftBlock;
import dev.simulated_team.simulated.content.blocks.util.AbstractDirectionalAxisBlock;
import dev.simulated_team.simulated.data.SimBlockStateGen;
import dev.simulated_team.simulated.data.neoforge.AugerShaftGen;
import dev.simulated_team.simulated.service.SimBlockStateService;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ModelFile;

import java.util.function.BiFunction;
import java.util.function.Function;

public class NeoForgeSimBlockStateService implements SimBlockStateService {

    @Override
    public <T extends Block> void genericModelBuilder(final DataGenContext<Block, T> ctx, final RegistrateBlockstateProvider prov, final Function<BlockState, SimBlockStateGen.XYHolder> xyGetter, final Function<BlockState, Object> modelGetter) {
        prov.getVariantBuilder(ctx.getEntry())
                .forAllStates(state -> {
                    if (modelGetter.apply(state) instanceof final ModelFile model) {
                        final SimBlockStateGen.XYHolder rotations = xyGetter.apply(state);
                        return ConfiguredModel.builder()
                                .modelFile(model)
                                .rotationX(rotations.xRot())
                                .rotationY(rotations.yRot())
                                .build();
                    } else {
                        throw new IllegalArgumentException("ModelGetter must return a ModelFile");
                    }
                });
    }

    @Override
    public <P extends AugerShaftBlock> NonNullBiConsumer<DataGenContext<Block, P>, RegistrateBlockstateProvider> augerShaftGenerate(final String name, final boolean cog) {
        return AugerShaftGen.generate(name, cog);
    }

    @Override
    public <T extends AbstractDirectionalAxisBlock> void directionalAxisBlock(final DataGenContext<Block, T> ctx, final RegistrateBlockstateProvider prov,
                                                                              final BiFunction<BlockState, Boolean, Object> modelFunc) {
        prov.getVariantBuilder(ctx.getEntry())
                .forAllStates(state -> {

                    final boolean alongFirst = state.getValue(AbstractDirectionalAxisBlock.AXIS_ALONG_FIRST_COORDINATE);
                    final Direction direction = state.getValue(AbstractDirectionalAxisBlock.FACING);
                    final boolean vertical = direction.getAxis()
                            .isHorizontal() && (direction.getAxis() == Direction.Axis.X) == alongFirst;
                    final int xRot = direction == Direction.DOWN ? 270 : direction == Direction.UP ? 90 : 0;
                    final int yRot = direction.getAxis()
                            .isVertical() ? alongFirst ? 0 : 90 : (int) direction.toYRot();

                    final Object model = modelFunc.apply(state, vertical);
                    if (!(model instanceof final ModelFile m))
                        throw new AssertionError("Required Model file!");

                    return ConfiguredModel.builder()
                            .modelFile(m)
                            .rotationX(xRot)
                            .rotationY(yRot)
                            .build();
                });
    }

}
