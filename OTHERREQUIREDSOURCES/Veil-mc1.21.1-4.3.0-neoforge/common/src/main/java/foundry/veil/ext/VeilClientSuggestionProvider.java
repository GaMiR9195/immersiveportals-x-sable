package foundry.veil.ext;

import net.minecraft.resources.ResourceLocation;

import java.util.stream.Stream;

public interface VeilClientSuggestionProvider {

    Stream<ResourceLocation> veil$getPostPipelineNames();
}
