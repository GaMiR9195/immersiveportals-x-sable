package foundry.veil.impl;

import foundry.veil.Veil;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import org.jetbrains.annotations.ApiStatus;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;

import java.util.*;

@ApiStatus.Internal
public abstract class VeilMixinPlugin implements IMixinConfigPlugin {

    private static final String PACKAGE_NAME = Veil.platform().getPlatformType().getMixinPackageName();
    private static final Set<String> COMPAT = Set.of(
            "foundry.veil." + PACKAGE_NAME + ".mixin.client.stage",
            "foundry.veil." + PACKAGE_NAME + ".mixin.client.perspective",
            "foundry.veil." + PACKAGE_NAME + ".mixin.client.debug"
    );
    private static final Map<String, Set<String>> INCOMPATIBLE_MIXINS = new Object2ObjectArrayMap<>();
    private final Map<String, Boolean> loadedMods = new HashMap<>();

    static {
        addModIncompatibility("affinity", "foundry.veil.mixin.performance.client.PerformanceRenderTargetMixin", "foundry.veil.mixin.performance.client.PerformanceLevelRendererMixin");
        addModIncompatibility("hdr_mod", "foundry.veil.mixin.performance.client.PerformanceRenderTargetMixin", "foundry.veil.mixin.performance.client.PerformanceLevelRendererMixin");
        addModIncompatibility("soulshade", "foundry.veil.mixin.performance.client.PerformanceRenderTargetMixin", "foundry.veil.mixin.performance.client.PerformanceLevelRendererMixin");
    }

    private static void addModIncompatibility(String modId, String... mixinClasses) {
        INCOMPATIBLE_MIXINS.computeIfAbsent(modId, a -> new ObjectArraySet<>()).addAll(Arrays.asList(mixinClasses));
    }

    private boolean isModLoaded(String modId) {
        return this.loadedMods.computeIfAbsent(modId, Veil.platform()::isModLoaded);
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        for (String compat : COMPAT) {
            if (mixinClassName.startsWith(compat)) {
                return Veil.SODIUM ? !mixinClassName.startsWith(compat + ".vanilla") : !mixinClassName.startsWith(compat + ".sodium");
            }
        }
        if (Veil.IRIS && mixinClassName.startsWith("foundry.veil.mixin.dynamicbuffer")) {
            return false;
        }
        if (mixinClassName.startsWith("foundry.veil." + PACKAGE_NAME + ".mixin.compat")) {
            String[] parts = mixinClassName.split("\\.", 7);
            return this.isModLoaded(parts[5]);
        }
        for (Map.Entry<String, Set<String>> entry : INCOMPATIBLE_MIXINS.entrySet()) {
            if (this.isModLoaded(entry.getKey()) && entry.getValue().contains(mixinClassName)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }
}
