package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.neoforge.compatibility.flywheel.FlywheelCompatNeoForge.SubLevelFlwRenderState;
import ipl.sable.render.IplContraptionRenderPoses;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.level.Level;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Sable's contraption render poses, per client level: see {@link IplContraptionRenderPoses}. */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.neoforge.compatibility.flywheel.FlywheelCompatNeoForge", remap = false)
public abstract class SableContraptionPosesMixin {

	@ModifyExpressionValue(
		method = {"preVisualizationFrame", "createRenderInfo"},
		at = @At(
			value = "FIELD",
			target = "Ldev/ryanhcode/sable/neoforge/compatibility/flywheel/FlywheelCompatNeoForge;RENDER_POSES:Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;",
			opcode = Opcodes.GETSTATIC
		)
	)
	private static Long2ObjectMap<SubLevelFlwRenderState> ipl$posesOfLevel(
		Long2ObjectMap<SubLevelFlwRenderState> shared, @Local(argsOnly = true) Level level
	) {
		return IplContraptionRenderPoses.of(level);
	}

	@ModifyExpressionValue(
		method = "getInfo",
		at = @At(
			value = "FIELD",
			target = "Ldev/ryanhcode/sable/neoforge/compatibility/flywheel/FlywheelCompatNeoForge;RENDER_POSES:Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;",
			opcode = Opcodes.GETSTATIC
		)
	)
	private static Long2ObjectMap<SubLevelFlwRenderState> ipl$posesOfVisualLevel(
		Long2ObjectMap<SubLevelFlwRenderState> shared
	) {
		return IplContraptionRenderPoses.of(null);
	}
}
