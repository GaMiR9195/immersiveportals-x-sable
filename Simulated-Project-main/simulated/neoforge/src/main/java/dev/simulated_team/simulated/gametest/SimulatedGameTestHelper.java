package dev.simulated_team.simulated.gametest;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.simulated_team.simulated.util.extra_kinetics.ExtraKinetics;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertPosException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.ToDoubleBiFunction;
import java.util.function.ToDoubleFunction;

public class SimulatedGameTestHelper {

    public static <T extends BlockEntity & ExtraKinetics> void assertExtraKinetics(final GameTestHelper helper, final BlockPos pos, final BiPredicate<T, KineticBlockEntity> predicate, final BiFunction<T, KineticBlockEntity, String> exceptionMessage) {
        final T t = helper.getBlockEntity(pos);
        if (!predicate.test(t, t.getExtraKinetics())) {
            throw new GameTestAssertPosException(exceptionMessage.apply(t, t.getExtraKinetics()), helper.absolutePos(pos), pos, helper.getTick());
        }
    }

    public static <T extends KineticBlockEntity> void assertKineticsSpeed(final GameTestHelper helper, final BlockPos pos, final ToDoubleFunction<T> speed, final double delta) {
        helper.<T>assertBlockEntityData(pos, be -> Math.abs(Math.abs(be.getSpeed()) - Math.abs(speed.applyAsDouble(be))) < delta, () -> {
            final T be = helper.getBlockEntity(pos);
            return "Expected %.2f speed, got %.2f".formatted(Math.abs(speed.applyAsDouble(be)), Math.abs(be.getSpeed()));
        });
    }

    public static <T extends KineticBlockEntity> void assertKineticsSpeed(final GameTestHelper helper, final BlockPos pos, final ToDoubleFunction<T> speed) {
        assertKineticsSpeed(helper, pos, speed, 1e-6);
    }

    public static void assertKineticsSpeed(final GameTestHelper helper, final BlockPos pos, final double speed, final double delta) {
        assertKineticsSpeed(helper, pos, be -> speed, delta);
    }

    public static void assertKineticsSpeed(final GameTestHelper helper, final BlockPos pos, final double speed) {
        assertKineticsSpeed(helper, pos, be -> speed, 1e-6);
    }

    public static <T extends KineticBlockEntity & ExtraKinetics> void assertExtraKineticsSpeed(final GameTestHelper helper, final BlockPos pos, final ToDoubleBiFunction<T, KineticBlockEntity> speed, final ToDoubleBiFunction<T, KineticBlockEntity> extraSpeed, final double delta) {
        SimulatedGameTestHelper.<T>assertExtraKinetics(helper, pos, (blockEntity, extraKinetics) -> Math.abs(Math.abs(blockEntity.getSpeed()) - speed.applyAsDouble(blockEntity, extraKinetics)) < delta &&
                        extraKinetics != null &&
                        Math.abs(Math.abs(extraKinetics.getSpeed()) - Math.abs(extraSpeed.applyAsDouble(blockEntity, extraKinetics))) < delta,
                (blockEntity, extraKinetics) -> {
                    if (extraKinetics == null) {
                        return "Expected extra kinetics, got null";
                    }
                    final double speedValue = Math.abs(speed.applyAsDouble(blockEntity, extraKinetics));
                    final double extraSpeedValue = Math.abs(extraSpeed.applyAsDouble(blockEntity, extraKinetics));
                    if (Math.abs(Math.abs(blockEntity.getSpeed()) - speedValue) >= delta) {
                        return "Expected %.2f speed, got %.2f".formatted(speedValue, Math.abs(blockEntity.getSpeed()));
                    }
                    return "Expected %.2f extra kinetics speed, got %.2f".formatted(Math.abs(extraSpeedValue), Math.abs(extraKinetics.getSpeed()));
                });
    }

    public static <T extends KineticBlockEntity & ExtraKinetics> void assertExtraKineticsSpeed(final GameTestHelper helper, final BlockPos pos, final ToDoubleBiFunction<T, KineticBlockEntity> speed, final ToDoubleBiFunction<T, KineticBlockEntity> extraSpeed) {
        assertExtraKineticsSpeed(helper, pos, speed, extraSpeed, 1e-6);
    }

    public static void assertExtraKineticsSpeed(final GameTestHelper helper, final BlockPos pos, final double speed, final double extraSpeed, final double delta) {
        assertExtraKineticsSpeed(helper, pos, (be, ebe) -> speed, (be, ebe) -> extraSpeed, 1e-6);
    }

    public static void assertExtraKineticsSpeed(final GameTestHelper helper, final BlockPos pos, final double speed, final double extraSpeed) {
        assertExtraKineticsSpeed(helper, pos, (be, ebe) -> speed, (be, ebe) -> extraSpeed, 1e-6);
    }
}
