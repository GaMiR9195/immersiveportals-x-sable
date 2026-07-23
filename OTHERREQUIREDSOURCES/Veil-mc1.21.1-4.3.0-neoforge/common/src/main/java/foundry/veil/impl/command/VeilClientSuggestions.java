package foundry.veil.impl.command;

import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.impl.client.editor.PostInspector;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

import java.util.stream.Stream;

@ApiStatus.Internal
public final class VeilClientSuggestions {

    private VeilClientSuggestions() {
    }

    public static Stream<ResourceLocation> getPostPipelineNames() {
        return VeilRenderSystem.renderer().getPostProcessingManager().getPipelines().stream().filter(pipeline -> !PostInspector.isInternal(pipeline));
    }
}
