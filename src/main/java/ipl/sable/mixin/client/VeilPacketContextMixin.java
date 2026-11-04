package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import foundry.veil.api.network.handler.PacketContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.ClientWorldLoader;

/**
 * Veil's {@link PacketContext#level()} returns the IP-redirected dimension while a Sable packet
 * is dispatched under {@code ClientWorldLoader.withSwitchedWorld}.
 *
 * <p>Veil's default reads {@code player.level()}, the dimension the player is in. Under IP's
 * redirected dispatch {@code Minecraft.level} is swapped to the dimension the packet came from
 * while the player stays where it is; the packet belongs to {@code Minecraft.level}, so Sable's
 * handlers mutate the container of that dimension.
 *
 * <p>Only the packet context is redirected, not {@code SubLevelContainer.getContainer(Level)}:
 * IP swaps {@code Minecraft.level} for portal-view rendering as well, and code that asks for a
 * specific level's container (renderers, explicit per-dimension lookups) must get that one.
 *
 * <p>Client only: server contexts override {@code level()} without this default.
 */
@Pseudo
@Mixin(value = PacketContext.class, remap = false)
public interface VeilPacketContextMixin {

    @ModifyReturnValue(method = "level", at = @At("RETURN"), require = 1)
    default Level ipl$redirectLevelDuringIpSwap(Level original) {
        if (!ClientWorldLoader.getIsWorldSwitched()) {
            return original;
        }
        Level mcLevel = Minecraft.getInstance().level;
        if (mcLevel == null || mcLevel == original) {
            return original;
        }
        return mcLevel;
    }
}
