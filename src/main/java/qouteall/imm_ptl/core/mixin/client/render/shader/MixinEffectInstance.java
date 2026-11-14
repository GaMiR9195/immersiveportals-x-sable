package qouteall.imm_ptl.core.mixin.client.render.shader;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.shaders.EffectProgram;
import com.mojang.blaze3d.shaders.Program;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.render.ShaderCodeTransformation;

/** Post-chain programs draw full-screen quads: they never take the world clip planes. */
@Mixin(EffectInstance.class)
public class MixinEffectInstance {
	@WrapMethod(method = "getOrCreate")
	private static EffectProgram ip_compileUnclipped(
		ResourceProvider resourceProvider, Program.Type type, String name, Operation<EffectProgram> original
	) {
		return ShaderCodeTransformation.compilingScreenSpacePrograms(
			() -> original.call(resourceProvider, type, name));
	}
}
