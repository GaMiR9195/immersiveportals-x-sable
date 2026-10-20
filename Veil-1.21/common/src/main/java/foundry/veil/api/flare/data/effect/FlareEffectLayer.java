package foundry.veil.api.flare.data.effect;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import foundry.veil.api.client.property.Property;
import foundry.veil.api.client.property.properties.TimeProperty;
import foundry.veil.api.client.registry.PropertyModifierRegistry;
import foundry.veil.api.client.render.MatrixStack;
import foundry.veil.api.flare.EffectHost;
import foundry.veil.api.flare.FlareEffectManager;
import foundry.veil.api.flare.model.BakedShell;
import foundry.veil.api.flare.modifier.ControllerManager;
import foundry.veil.api.flare.modifier.PropertyModifier;
import foundry.veil.api.util.CodecUtil;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @since 2.5.0
 */
public final class FlareEffectLayer {

    public static final Codec<FlareEffectLayer> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("name").forGetter(FlareEffectLayer::getName),
            Codec.BOOL.optionalFieldOf("disabled", false).forGetter(FlareEffectLayer::isDisabled),
            FlareModel.CODEC.fieldOf("model").forGetter(FlareEffectLayer::getModel),
            CodecUtil.registryOrLegacyCodec(PropertyModifierRegistry.REGISTRY)
                    .<PropertyModifier<?>>dispatch(PropertyModifier::type, PropertyModifierRegistry.PropertyModifierType::codec)
                    .listOf()
                    .optionalFieldOf("modifiers", List.of())
                    .forGetter(layer -> List.of(layer.originalModifiers))
    ).apply(instance, FlareEffectLayer::new));

    private final String name;
    private final boolean disabled;
    private final FlareModel model;
    private final Map<String, List<PropertyModifier<?>>> modifiers;
    private final PropertyModifier<?>[] originalModifiers;

    private final List<PropertyModifier<?>> positionModifier;
    private final List<PropertyModifier<?>> rotationModifier;
    private final List<PropertyModifier<?>> scaleModifier;

    public FlareEffectLayer(String name, boolean disabled, FlareModel model, List<PropertyModifier<?>> modifiers) {
        this.name = name;
        this.disabled = disabled;
        this.model = model;
        this.originalModifiers = modifiers.toArray(PropertyModifier[]::new);

        Map<String, List<PropertyModifier<?>>> modifierMap = new HashMap<>();

        for (FlareMaterial material : model.getMaterials()) {
            this.putModelProperties(material.properties());
        }

        for (PropertyModifier<?> modifier : modifiers) {
            List<PropertyModifier<?>> modifierList = modifierMap.computeIfAbsent(modifier.outputPropertyName(), unused -> new ObjectArrayList<>());
            modifierList.add(modifier);
        }

        modifierMap.replaceAll((key, value) -> Collections.unmodifiableList(value));
        this.modifiers = Collections.unmodifiableMap(new Object2ObjectArrayMap<>(modifierMap));

        this.positionModifier = modifierMap.get(FlareModel.POSITION_PROPERTY_NAME);
        this.rotationModifier = modifierMap.get(FlareModel.ROTATION_PROPERTY_NAME);
        this.scaleModifier = modifierMap.get(FlareModel.SCALE_PROPERTY_NAME);
    }

    public void putModelProperties(Map<String, Property<?>> materialProperties) {
        materialProperties.put(FlareModel.POSITION_PROPERTY_NAME, this.model.positionOffset);
        materialProperties.put(FlareModel.ROTATION_PROPERTY_NAME, this.model.rotationOffset);
        materialProperties.put(FlareModel.SCALE_PROPERTY_NAME, this.model.scaleOffset);
        materialProperties.put("ModelToWorld", this.model.modelToWorld);
        materialProperties.put("_Time", TimeProperty.INSTANCE);
    }

    public void render(EffectHost host, MatrixStack matrixStack, float partialTick, @Nullable Map<ResourceLocation, BakedShell> shellOverrides) {
        ControllerManager controllerManager = FlareEffectManager.getInstance().getControllerManager();

        for (PropertyModifier<?> modifier : this.originalModifiers) {
            controllerManager.getOrCreateController(modifier.inputControllerName(), host).update(partialTick);
        }

        PropertyModifier.modifyProperty(host, null, this.model.positionOffset, this.positionModifier);
        PropertyModifier.modifyProperty(host, null, this.model.rotationOffset, this.rotationModifier);
        PropertyModifier.modifyProperty(host, null, this.model.scaleOffset, this.scaleModifier);

        this.model.render(host, matrixStack, this.modifiers, shellOverrides);

        this.model.positionOffset.resetOverrideValue();
        this.model.rotationOffset.resetOverrideValue();
        this.model.scaleOffset.resetOverrideValue();
        this.model.modelToWorld.resetOverrideValue();
    }

    public String getName() {
        return this.name;
    }

    public FlareModel getModel() {
        return this.model;
    }

    public Map<String, List<PropertyModifier<?>>> getModifiers() {
        return this.modifiers;
    }

    public PropertyModifier<?>[] getOriginalModifiers() {
        return this.originalModifiers;
    }

    public boolean isDisabled() {
        return this.disabled;
    }
}
