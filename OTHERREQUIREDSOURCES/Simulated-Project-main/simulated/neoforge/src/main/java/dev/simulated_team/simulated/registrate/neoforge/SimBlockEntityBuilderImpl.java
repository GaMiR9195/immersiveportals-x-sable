package dev.simulated_team.simulated.registrate.neoforge;

import com.tterrag.registrate.AbstractRegistrate;
import com.tterrag.registrate.builders.BlockEntityBuilder;
import com.tterrag.registrate.builders.BuilderCallback;
import com.tterrag.registrate.util.OneTimeEventReceiver;
import com.tterrag.registrate.util.nullness.NonNullSupplier;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import dev.simulated_team.simulated.Simulated;
import dev.simulated_team.simulated.mixin.accessor.CreateBlockEntityBuilderAccessor;
import dev.simulated_team.simulated.registrate.SimBlockEntityBuilder;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

import java.util.function.Predicate;

@SuppressWarnings("unchecked")
public class SimBlockEntityBuilderImpl<T extends BlockEntity, P> extends SimBlockEntityBuilder<T, P> {
    protected SimBlockEntityBuilderImpl(final AbstractRegistrate<?> owner, final P parent, final String name, final BuilderCallback callback, final BlockEntityFactory<T> factory) {
        super(owner, parent, name, callback, factory);
    }

    public static <T extends BlockEntity, P> BlockEntityBuilder<T, P> create(final AbstractRegistrate<?> owner, final P parent, final String name, final BuilderCallback callback, final BlockEntityBuilder.BlockEntityFactory<T> factory) {
        return new SimBlockEntityBuilderImpl(owner, parent, name, callback, factory);
    }

    @Override
    protected void registerVisualizer() {

        OneTimeEventReceiver.addModListener(Simulated.getRegistrate(), FMLClientSetupEvent.class, ($) -> {
            final NonNullSupplier<SimpleBlockEntityVisualizer.Factory<T>> visualFactory = ((CreateBlockEntityBuilderAccessor<T, P>) this).getVisualFactory();
            if (visualFactory != null) {
                final Predicate<T> renderNormally = ((CreateBlockEntityBuilderAccessor<T, P>)this).getRenderNormally();
                SimpleBlockEntityVisualizer.builder((BlockEntityType) this.getEntry()).factory(visualFactory.get()).skipVanillaRender((be) -> {
                    return !renderNormally.test((T) be);
                }).apply();
            }

        });
    }

}
