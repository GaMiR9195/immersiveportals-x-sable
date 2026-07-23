package dev.simulated_team.simulated.mixin.accessor;

import com.simibubi.create.content.redstone.link.LinkBehaviour;
import com.simibubi.create.content.redstone.link.RedstoneLinkBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RedstoneLinkBlockEntity.class)
public interface RedstoneLinkBlockEntityAccessor {

    @Accessor("link")
    LinkBehaviour getLink();


}
