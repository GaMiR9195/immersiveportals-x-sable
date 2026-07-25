package dev.simulated_team.simulated.index.neoforge;

import com.mojang.serialization.Codec;
import com.simibubi.create.foundation.recipe.IRecipeTypeInfo;
import dev.simulated_team.simulated.Simulated;
import dev.simulated_team.simulated.data.neoforge.PortableEngineDyeingRecipe;
import net.createmod.catnip.lang.Lang;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.crafting.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

public enum SimNeoForgeRecipeTypes implements IRecipeTypeInfo, StringRepresentable {

    PORTABLE_ENGINE_DYEING(() -> new SimpleCraftingRecipeSerializer<>(PortableEngineDyeingRecipe::new), () -> RecipeType.CRAFTING, false);

    public static final Codec<SimNeoForgeRecipeTypes> CODEC = StringRepresentable.fromEnum(SimNeoForgeRecipeTypes::values);
    public final ResourceLocation id;
    public final Supplier<RecipeSerializer<?>> serializerSupplier;
    private final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<?>> serializerObject;
    @Nullable
    private final DeferredHolder<RecipeType<?>, RecipeType<?>> typeObject;
    private final Supplier<RecipeType<?>> type;

    SimNeoForgeRecipeTypes(final Supplier<RecipeSerializer<?>> serializerSupplier, final Supplier<RecipeType<?>> typeSupplier, final boolean registerType) {
        final String name = Lang.asId(this.name());
        this.id = Simulated.path(name);
        this.serializerSupplier = serializerSupplier;
        this.serializerObject = Registers.SERIALIZER_REGISTER.register(name, serializerSupplier);

        if (registerType) {
            this.typeObject = Registers.TYPE_REGISTER.register(name, typeSupplier);
            this.type = this.typeObject;
        } else {
            this.typeObject = null;
            this.type = typeSupplier;
        }
    }

    @ApiStatus.Internal
    public static void register(final IEventBus modEventBus) {
        Registers.SERIALIZER_REGISTER.register(modEventBus);
        Registers.TYPE_REGISTER.register(modEventBus);
    }

    @Override
    public ResourceLocation getId() {
        return this.id;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends RecipeSerializer<?>> T getSerializer() {
        return (T) this.serializerObject.get();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <I extends RecipeInput, R extends Recipe<I>> RecipeType<R> getType() {
        return (RecipeType<R>) this.type.get();
    }

    @Override
    public @NotNull String getSerializedName() {
        return this.id.toString();
    }

    private static class Registers {
        private static final DeferredRegister<RecipeSerializer<?>> SERIALIZER_REGISTER = DeferredRegister.create(BuiltInRegistries.RECIPE_SERIALIZER, Simulated.MOD_ID);
        private static final DeferredRegister<RecipeType<?>> TYPE_REGISTER = DeferredRegister.create(Registries.RECIPE_TYPE, Simulated.MOD_ID);
    }

}
