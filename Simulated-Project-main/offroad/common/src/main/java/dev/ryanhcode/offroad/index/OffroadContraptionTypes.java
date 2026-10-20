package dev.ryanhcode.offroad.index;

import com.simibubi.create.api.contraption.ContraptionType;
import com.simibubi.create.api.registry.CreateBuiltInRegistries;
import com.simibubi.create.content.contraptions.Contraption;
import dev.ryanhcode.offroad.Offroad;
import dev.ryanhcode.offroad.content.contraptions.borehead_contraption.BoreheadBearingContraption;
import foundry.veil.platform.registry.RegistrationProvider;
import foundry.veil.platform.registry.RegistryObject;

import java.util.function.Supplier;

public class OffroadContraptionTypes {

    private static final RegistrationProvider<ContraptionType> OFFROAD_CONTRAPTION_TYPES_REGISTRY = RegistrationProvider.get(CreateBuiltInRegistries.CONTRAPTION_TYPE, Offroad.MOD_ID);

    public static final RegistryObject<ContraptionType> BOREHEAD_CONTRAPTION_TYPE = register("borehead_contraption", BoreheadBearingContraption::new);

    private static RegistryObject<ContraptionType> register(String name, Supplier<? extends Contraption> factory) {
        return OFFROAD_CONTRAPTION_TYPES_REGISTRY.register(name, () -> new ContraptionType(factory));
    }

    public static void init() {}

}
