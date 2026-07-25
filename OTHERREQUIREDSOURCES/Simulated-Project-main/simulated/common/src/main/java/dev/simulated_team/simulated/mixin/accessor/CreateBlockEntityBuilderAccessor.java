package dev.simulated_team.simulated.mixin.accessor;

import com.simibubi.create.foundation.data.CreateBlockEntityBuilder;
import com.tterrag.registrate.util.nullness.NonNullSupplier;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Predicate;

@Mixin(CreateBlockEntityBuilder.class)
public interface CreateBlockEntityBuilderAccessor<T extends BlockEntity, P> {

    @Accessor
    NonNullSupplier<SimpleBlockEntityVisualizer.Factory<T>> getVisualFactory();

    @Accessor
    Predicate<@NotNull T> getRenderNormally();

}
