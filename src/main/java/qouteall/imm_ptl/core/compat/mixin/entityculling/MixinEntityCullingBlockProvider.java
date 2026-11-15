package qouteall.imm_ptl.core.compat.mixin.entityculling;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.compat.EntityCullingCompat;

/**
 * EntityCulling's tracing thread reads {@code Minecraft.level}, which IP points at another
 * dimension while it renders a portal. The tracer traces from the player's camera, so it reads
 * the player's level instead.
 */
@Pseudo
@Mixin(
	targets = {
		"dev.tr7zw.entityculling.provider.DirectProvider",
		"dev.tr7zw.entityculling.provider.SimpleCacheProvider"
	},
	remap = false
)
public class MixinEntityCullingBlockProvider {
	@Redirect(
		method = "prepareChunk",
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
