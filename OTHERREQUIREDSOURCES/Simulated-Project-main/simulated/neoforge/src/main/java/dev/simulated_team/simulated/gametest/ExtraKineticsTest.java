package dev.simulated_team.simulated.gametest;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.redstone.analogLever.AnalogLeverBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.simulated_team.simulated.Simulated;
import dev.simulated_team.simulated.content.blocks.analog_transmission.AnalogTransmissionBlockEntity;
import dev.simulated_team.simulated.content.blocks.torsion_spring.TorsionSpringBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.LeverBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import org.joml.Vector3d;

import java.util.Objects;

import static dev.simulated_team.simulated.gametest.SimulatedGameTestHelper.assertExtraKineticsSpeed;
import static dev.simulated_team.simulated.gametest.SimulatedGameTestHelper.assertKineticsSpeed;

@GameTestHolder(Simulated.MOD_ID)
public class ExtraKineticsTest {

    @GameTest
    public static void analogTransmission(final GameTestHelper helper) {
        final AnalogLeverBlockEntity leverBE = helper.getBlockEntity(new BlockPos(1, 2, 1));
        final GameTestSequence sequence = helper.startSequence();
        for (int i = 0; i < 16; i++) {
            sequence.thenExecuteAfter(1, () -> {
                switch (leverBE.getState()) {
                    case 0 -> assertKineticsSpeed(helper, new BlockPos(1, 2, 2), 16);
                    case 15 -> assertKineticsSpeed(helper, new BlockPos(1, 2, 2), 0);
                    default ->
                            SimulatedGameTestHelper.<AnalogTransmissionBlockEntity>assertKineticsSpeed(helper, new BlockPos(1, 2, 2), be -> 16 / be.getRotationModifier());
                }
            }).thenExecuteAfter(1, () -> leverBE.changeState(false));
        }
        sequence.thenSucceed();
    }

    @GameTest
    public static void analogTransmissionReverse(final GameTestHelper helper) {
        final AnalogLeverBlockEntity leverBE = helper.getBlockEntity(new BlockPos(1, 2, 1));
        final GameTestSequence sequence = helper.startSequence();
        for (int i = 0; i < 16; i++) {
            sequence.thenExecuteAfter(1, () -> {
                switch (leverBE.getState()) {
                    case 0 -> assertExtraKineticsSpeed(helper, new BlockPos(1, 2, 2), 16, 16);
                    case 15 -> assertExtraKineticsSpeed(helper, new BlockPos(1, 2, 2), 16, 0);
                    default ->
                            SimulatedGameTestHelper.<AnalogTransmissionBlockEntity>assertExtraKineticsSpeed(helper, new BlockPos(1, 2, 2), (be, ebe) -> 16, (be, ebe) -> 16 * be.getRotationModifier());
                }
            }).thenExecuteAfter(1, () -> leverBE.changeState(false));
        }
        sequence.thenSucceed();
    }

    @GameTest
    public static void swivelBearing(final GameTestHelper helper) {
        helper.startSequence()
                .thenExecuteAfter(1, () -> assertExtraKineticsSpeed(helper, new BlockPos(2, 3, 2), 64, -32))
                .thenIdle(20)
                .thenExecute(() -> {
                    int count = 0;
                    SubLevel subLevel = null;
                    for (final SubLevel l : Sable.HELPER.getAllIntersecting(helper.getLevel(), new BoundingBox3d(helper.getBounds()))) {
                        count++;
                        subLevel = l;
                    }

                    if (count != 1) {
                        throw new GameTestAssertException("Expected 1 sub-level, found " + count);
                    }

                    final KineticBlockEntity be = (KineticBlockEntity) Objects.requireNonNull(subLevel.getLevel().getBlockEntity(subLevel.getPlot().getCenterBlock()));
                    if (Math.abs(Math.abs(be.getSpeed()) - 64) >= 1e-6) {
                        final Vector3d pos = subLevel.logicalPose().position();
                        throw new GameTestAssertPosException("Expected %.2f speed, got %.2f".formatted(64F, Math.abs(be.getSpeed())), BlockPos.containing(pos.x, pos.y, pos.z), BlockPos.containing(helper.relativeVec(JOMLConversion.toMojang(pos))), helper.getTick());
                    }
                })
                .thenSucceed();
    }

    @GameTest
    public static void torsionSpring(final GameTestHelper helper) {
        helper.startSequence()
                .thenExecuteAfter(1, () -> assertKineticsSpeed(helper, new BlockPos(2, 2, 3), 32))
                .thenExecuteAfter(15, () -> helper.<TorsionSpringBlockEntity>assertBlockEntityData(new BlockPos(2, 2, 3), be -> Math.abs(be.getAngle()) == 90, () -> "Expected 90 degrees, got %.0f".formatted(Math.abs(helper.<TorsionSpringBlockEntity>getBlockEntity(new BlockPos(2, 2, 3)).getAngle()))))
                .thenExecuteAfter(1, () -> helper.setBlock(1, 2, 2, helper.getBlockState(new BlockPos(1, 2, 2)).setValue(LeverBlock.POWERED, true)))
                .thenExecuteAfter(15, () -> helper.<TorsionSpringBlockEntity>assertBlockEntityData(new BlockPos(2, 2, 3), be -> be.getAngle() == 0, () -> "Expected 0 degrees, got %.0f".formatted(Math.abs(helper.<TorsionSpringBlockEntity>getBlockEntity(new BlockPos(2, 2, 3)).getAngle()))))
                .thenSucceed();
    }
}
