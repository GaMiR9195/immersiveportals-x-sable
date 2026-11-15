package qouteall.imm_ptl.core.compat.mixin.entityculling;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.compat.EntityCullingCompat;

/** The entity list EntityCulling traces may be collected on its own thread: keep it on the player's level. */
@Pseudo
@Mixin(targets = "dev.tr7zw.entityculling.EntityCullingModBase", remap = false)
public class MixinEntityCullingModBase {
	@Redirect(
		method = "extractEntityData",
		at = @At(
			value = "FIELD",
			target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;",
			opcode = Opcodes.GETFIELD
		),
		require = 0
	)
	private ClientLevel ip_tracedLevel(Minecraft client) {
		return EntityCullingCompat.getPlayerLevel(client);
	}
}
