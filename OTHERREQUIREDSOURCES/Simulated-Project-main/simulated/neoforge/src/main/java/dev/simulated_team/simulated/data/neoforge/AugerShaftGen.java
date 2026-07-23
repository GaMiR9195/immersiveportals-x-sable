package dev.simulated_team.simulated.data.neoforge;

import com.tterrag.registrate.providers.DataGenContext;
import com.tterrag.registrate.providers.RegistrateBlockstateProvider;
import com.tterrag.registrate.util.nullness.NonNullBiConsumer;
import dev.simulated_team.simulated.Simulated;
import dev.simulated_team.simulated.content.blocks.auger_shaft.AugerShaftBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.client.model.generators.MultiPartBlockStateBuilder;

import static net.minecraft.core.Direction.UP;

public class AugerShaftGen {

    public static ConfiguredModel.Builder<MultiPartBlockStateBuilder.PartBuilder> rotate(final Direction direction, final ConfiguredModel.Builder<MultiPartBlockStateBuilder.PartBuilder> builder) {
        builder.rotationX(direction.getAxis().isHorizontal() ? -90 : (direction == UP ? 180 : 0));
        builder.rotationY(direction.getAxis().isVertical() ? 0 : (((int) direction.toYRot()) + 180) % 360);
        return builder;
    }

    public static <P extends AugerShaftBlock> NonNullBiConsumer<DataGenContext<Block, P>, RegistrateBlockstateProvider> generate(
            final String name, final boolean cog) {
        return (c, p) -> {

            final ModelFile axis_y = cog ? sub(p, name, "cog_axis_y") : sub(p, name, "axis_y");
            final ModelFile connection_top = sub(p, name, "connection_top");

            final MultiPartBlockStateBuilder builder = p.getMultipartBuilder(c.get());

            //rotate main body
            for (final Direction.Axis dir : Direction.Axis.values()) {
                rotate(Direction.get(Direction.AxisDirection.POSITIVE, dir), builder.part().modelFile(axis_y))
                        .addModel()
                        .condition(AugerShaftBlock.AXIS, dir)
                        .condition(AugerShaftBlock.ENCASED, false)
                        .end();
            }

            //rotate encased body
            for (final Direction.Axis dir : Direction.Axis.values()) {
                rotate(Direction.get(Direction.AxisDirection.POSITIVE, dir), builder.part().modelFile(sub(p, name, "axis_y_encased")))
                        .addModel()
                        .condition(AugerShaftBlock.AXIS, dir)
                        .condition(AugerShaftBlock.ENCASED, true)
                        .end();
            }

            //Generate start and end segments
            for (final Direction.Axis dir : Direction.Axis.values()) {
                rotate(Direction.get(Direction.AxisDirection.POSITIVE, dir), builder.part().modelFile(connection_top))
                        .addModel()
                        .condition(AugerShaftBlock.AXIS, dir)
                        .condition(AugerShaftBlock.SECTION, AugerShaftBlock.BarrelSection.END, AugerShaftBlock.BarrelSection.SINGLE)
                        .condition(AugerShaftBlock.ENCASED, false)
                        .end();

                rotate(Direction.get(Direction.AxisDirection.NEGATIVE, dir), builder.part().modelFile(connection_top))
                        .addModel()
                        .condition(AugerShaftBlock.AXIS, dir)
                        .condition(AugerShaftBlock.SECTION, AugerShaftBlock.BarrelSection.FRONT, AugerShaftBlock.BarrelSection.SINGLE)
                        .condition(AugerShaftBlock.ENCASED, false)
                        .end();
            }

            //generate connection points
            if (!cog) {
                for (final Direction dir : Direction.values()) {

                    rotate(dir.getOpposite(), builder.part().modelFile(sub(p, name, "bracket_top_" + dir.getAxis().getName())))
                            .addModel()
                            .condition(AugerShaftBlock.PROPERTY_BY_DIRECTION.get(dir), true)
                            .condition(AugerShaftBlock.ENCASED, false)
                            .end();
                }
            }
        };
    }

    private static ModelFile sub(final RegistrateBlockstateProvider p, final String name, final String suffix) {
        return p.models().getExistingFile(Simulated.path("block/auger_shaft/" + suffix));
    }
}
