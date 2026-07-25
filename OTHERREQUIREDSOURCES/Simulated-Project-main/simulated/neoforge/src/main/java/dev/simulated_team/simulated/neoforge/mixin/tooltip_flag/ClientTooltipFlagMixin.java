package dev.simulated_team.simulated.neoforge.mixin.tooltip_flag;

import dev.simulated_team.simulated.mixin_interface.tooltip_flag.TooltipFlagExtension;
import net.neoforged.neoforge.client.ClientTooltipFlag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ClientTooltipFlag.class)
public class ClientTooltipFlagMixin implements TooltipFlagExtension {
    @Unique
    private boolean sable$creativeSearch;

    @Override
    public boolean simulated$getCreativeSearch() {
        return this.sable$creativeSearch;
    }

    @Override
    public void simulated$setCreativeSearch(final boolean value) {
        this.sable$creativeSearch = value;
    }
}
