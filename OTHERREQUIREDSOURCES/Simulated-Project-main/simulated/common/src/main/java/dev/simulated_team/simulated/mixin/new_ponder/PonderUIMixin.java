package dev.simulated_team.simulated.mixin.new_ponder;

import dev.simulated_team.simulated.ponder.new_ponder_tooltip.NewPonderTooltipManager;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.ui.PonderUI;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(PonderUI.class)
public class PonderUIMixin {
    @Shadow @Final private List<PonderScene> scenes;
    @Shadow private int index;

    @Inject(method = "scroll", at = @At(value = "INVOKE", target = "Lnet/createmod/ponder/foundation/PonderScene;begin()V"))
    private void simulated$begin(final boolean forward, final CallbackInfoReturnable<Boolean> cir) {
        NewPonderTooltipManager.setSceneWatched(this.scenes.get(this.index).getId());
    }
}
