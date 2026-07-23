package top.leonx.irisveil;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.gametest.framework.GameTestRegistry;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.gametest.GameTestHooks;

@Mod(IrisVeilCompat.MODID)
public class IrisVeilCompat {
    public static final String MODID = "irisveil";
    public static final Logger LOGGER = LogUtils.getLogger();

    public IrisVeilCompat(IEventBus modEventBus, ModContainer modContainer) {
        // Register game tests directly — GameTestRegistry.register() uses NeoForge's
        // patched code path that automatically applies template namespace prefixing.
        if (GameTestHooks.isGametestServer()) {
            GameTestRegistry.register(top.leonx.irisveil.test.IrisVeilGameTest.class);
        }
    }

    public static boolean isShaderPackInUse() {
        try {
            return net.irisshaders.iris.Iris.getIrisConfig().areShadersEnabled()
                && net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable() != null;
        } catch (Throwable e) {
            return false;
        }
    }
}
