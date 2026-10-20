package dev.simulated_team.simulated.mixin.tooltip_flag;

import dev.simulated_team.simulated.mixin_interface.tooltip_flag.TooltipFlagExtension;
import net.minecraft.client.multiplayer.SessionSearchTrees;
import net.minecraft.world.item.TooltipFlag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(SessionSearchTrees.class)
public class SessionSearchTreesMixin {
    @ModifyVariable(method = "lambda$updateCreativeTooltips$15", at = @At(value = "STORE"))
    private static TooltipFlag markAsCreativeSearch(final TooltipFlag value) {
        ((TooltipFlagExtension)value).simulated$setCreativeSearch(true);
        return value;
    }
}
