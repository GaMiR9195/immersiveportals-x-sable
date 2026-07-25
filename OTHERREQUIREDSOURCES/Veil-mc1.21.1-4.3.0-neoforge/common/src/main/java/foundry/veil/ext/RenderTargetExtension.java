package foundry.veil.ext;

import foundry.veil.api.client.render.framebuffer.AdvancedFbo;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public interface RenderTargetExtension {

    void veil$setWrapper(@Nullable AdvancedFbo fbo);

    int veil$getTexture(int index);
}
