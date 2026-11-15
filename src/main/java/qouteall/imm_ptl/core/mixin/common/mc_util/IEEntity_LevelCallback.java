package qouteall.imm_ptl.core.mixin.common.mc_util;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The entity's section callback, detached while a player acts through a portal. */
@Mixin(Entity.class)
public interface IEEntity_LevelCallback {
    @Accessor("levelCallback")
    EntityInLevelCallback ip_getLevelCallback();
}
