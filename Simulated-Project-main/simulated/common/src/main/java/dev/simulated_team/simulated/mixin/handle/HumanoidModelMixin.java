package dev.simulated_team.simulated.mixin.handle;

import dev.simulated_team.simulated.content.blocks.handle.PlayerHoldingHandleRenderer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HumanoidModel.class)
public class HumanoidModelMixin<T extends LivingEntity> {
    @Shadow
    @Final
    public ModelPart body;

    @Inject(method = "setupAnim*", at = @At("RETURN"))
    private void simulated$afterSetupAnim(final T pEntity, final float pLimbSwing, final float pLimbSwingAmount, final float pAgeInTicks, final float pNetHeadYaw, final float pHeadPitch, final CallbackInfo callbackInfo) {
        if (!(pEntity instanceof final AbstractClientPlayer player))
            return;

        PlayerHoldingHandleRenderer.afterSetupAnim(player, (HumanoidModel<?>) (Object) this);
    }
}
