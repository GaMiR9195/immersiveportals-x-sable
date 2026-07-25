package foundry.veil.forge;

import foundry.veil.Veil;
import foundry.veil.VeilClient;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.forge.event.ForgeVeilRegisterFixedBuffersEvent;
import foundry.veil.forge.event.ForgeVeilRendererAvailableEvent;
import foundry.veil.forge.impl.ForgeRenderTypeStageHandler;
import foundry.veil.impl.VeilReloadListeners;
import foundry.veil.impl.client.imgui.VeilImGuiCompat;
import foundry.veil.impl.client.render.shader.VeilVanillaShaders;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
@Mod(value = Veil.MODID, dist = Dist.CLIENT)
public class VeilForgeClient {

    public VeilForgeClient(IEventBus modEventBus) {
        VeilClient.init();

        modEventBus.addListener(VeilForgeClient::registerKeys);
        modEventBus.addListener(VeilForgeClient::registerListeners);
        modEventBus.addListener(VeilForgeClient::registerShaders);
    }

    private static void registerListeners(RegisterClientReloadListenersEvent event) {
        VeilRenderSystem.init();
        VeilReloadListeners.registerListeners((type, id, listener) -> event.registerReloadListener(listener));
        ModLoader.postEvent(new ForgeVeilRendererAvailableEvent(VeilRenderSystem.renderer()));
        ModLoader.postEvent(new ForgeVeilRegisterFixedBuffersEvent(ForgeRenderTypeStageHandler::register));
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        if (Veil.IMGUIMC) {
            event.register(VeilImGuiCompat.EDITOR_KEY);
        }
    }

    private static void registerShaders(RegisterShadersEvent event) {
        try {
            VeilVanillaShaders.registerShaders((id, vertexFormat, loadCallback) -> event.registerShader(new ShaderInstance(event.getResourceProvider(), id, vertexFormat), loadCallback));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
