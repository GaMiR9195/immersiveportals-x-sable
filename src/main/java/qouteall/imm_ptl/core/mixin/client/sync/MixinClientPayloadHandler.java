package qouteall.imm_ptl.core.mixin.client.sync;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handlers.ClientPayloadHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * NeoForge applies the extra spawn data of an entity ({@code IEntityWithComplexSpawn}, e.g. the
 * structure of a Create contraption) and synced attachments to the entity found in the player's
 * level. A packet redirected from another dimension is handled with {@code Minecraft.level}
 * switched to that dimension while the player stays where it is, so the data went to an entity of
 * the player's level with the same id, or nowhere, and the entity in the other dimension stayed
 * without it (a contraption arriving through a portal was discarded by Create on its first tick).
 */
@Mixin(value = ClientPayloadHandler.class, remap = false)
public class MixinClientPayloadHandler {
    @Redirect(
        method = {
            "handle(Lnet/neoforged/neoforge/network/payload/AdvancedAddEntityPayload;Lnet/neoforged/neoforge/network/handling/IPayloadContext;)V",
            "handle(Lnet/neoforged/neoforge/network/payload/SyncAttachmentsPayload;Lnet/neoforged/neoforge/network/handling/IPayloadContext;)V"
        },
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;level()Lnet/minecraft/world/level/Level;"
        )
    )
    private static Level ip_handlingLevel(Player player) {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null ? level : player.level();
    }
}
