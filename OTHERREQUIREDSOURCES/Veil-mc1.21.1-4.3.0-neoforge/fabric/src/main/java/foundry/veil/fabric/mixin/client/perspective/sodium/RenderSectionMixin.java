package foundry.veil.fabric.mixin.client.perspective.sodium;

import foundry.veil.api.client.render.VeilLevelPerspectiveRenderer;
import foundry.veil.fabric.ext.RenderSectionExtension;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderSection.class)
public class RenderSectionMixin implements RenderSectionExtension {

    @Unique
    private int veil$perspectiveId;
    @Unique
    private int veil$incomingDirections;

    @Override
    public boolean veil$hasNotRendered() {
        return this.veil$perspectiveId != VeilLevelPerspectiveRenderer.getID();
    }

    @Override
    public void veil$markRendered() {
        this.veil$perspectiveId = VeilLevelPerspectiveRenderer.getID();
        this.veil$incomingDirections = 0;
    }

    @Override
    public void veil$addIncomingDirections(int directions) {
        this.veil$incomingDirections |= directions;
    }

    @Inject(method = "getIncomingDirections", at = @At("HEAD"), cancellable = true, remap = false)
    public void getIncomingDirections(CallbackInfoReturnable<Integer> cir) {
        if (VeilLevelPerspectiveRenderer.isRenderingPerspective()) {
            cir.setReturnValue(this.veil$incomingDirections);
        }
    }
}
