package foundry.veil.mixin.command.client;

import foundry.veil.ext.VeilClientSuggestionProvider;
import foundry.veil.impl.command.VeilClientSuggestions;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;

import java.util.stream.Stream;

@Mixin(ClientSuggestionProvider.class)
public class ClientCommandSourceMixin implements VeilClientSuggestionProvider {

    @Override
    public Stream<ResourceLocation> veil$getPostPipelineNames() {
        return VeilClientSuggestions.getPostPipelineNames();
    }
}
