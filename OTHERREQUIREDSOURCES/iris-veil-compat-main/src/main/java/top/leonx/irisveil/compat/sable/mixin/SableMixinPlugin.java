package top.leonx.irisveil.compat.sable.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import top.leonx.irisveil.IrisVeilCompat;

import java.util.List;
import java.util.Set;

public final class SableMixinPlugin implements IMixinConfigPlugin {
    private static final String[] REQUIRED_CLASSES = {
        "dev.ryanhcode.sable.api.sublevel.SubLevelContainer",
        "dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher",
        "dev.ryanhcode.sable.mixinhelpers.sublevel_render.vanilla.VanillaSubLevelBlockEntityRenderer"
    };

    private boolean applySableMixins;

    @Override
    public void onLoad(String mixinPackage) {
        applySableMixins = true;
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = SableMixinPlugin.class.getClassLoader();
        }

        for (String className : REQUIRED_CLASSES) {
            if (!hasClassResource(classLoader, className)) {
                applySableMixins = false;
                IrisVeilCompat.LOGGER.debug("Skipping Sable mixins because {} is unavailable", className);
                return;
            }
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return applySableMixins;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    private static boolean hasClassResource(ClassLoader classLoader, String className) {
        String resourceName = className.replace('.', '/') + ".class";
        return classLoader.getResource(resourceName) != null;
    }
}
