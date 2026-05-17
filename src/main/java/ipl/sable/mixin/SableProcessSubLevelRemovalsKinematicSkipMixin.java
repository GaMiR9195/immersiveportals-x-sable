package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipl.sable.iface.IplKinematicSubLevelHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Prevent Sable's {@code SubLevelContainer.processSubLevelRemovals} from auto-killing
 * kinematic mirrors.
 *
 * <p><b>The bug we're patching:</b> {@code processSubLevelRemovals} iterates all
 * sub-levels every tick and, for any whose mass tracker is invalid
 * ({@code getMass() <= 0}), calls {@code plot.destroyAllBlocks()} and then
 * {@code markRemoved()}. The {@code destroyAllBlocks()} call drops items for every
 * block in the plot — that's the "fountain of wool" the user observed at the mirror
 * position.
 *
 * <p>Our kinematic mirrors are externally driven: we pin the pipeline body's pose
 * each tick via {@code MirrorOps.syncMirrorPose -> pipeline.teleport}, and the
 * mirror's logicalPose is set directly from the portal transform. The mirror's mass
 * tracker is incidental — it doesn't drive simulation for us. But Sable doesn't
 * know that, so the mass-validity check (which can legitimately fail when blocks
 * are added via our copy path but {@code localBounds} hasn't fully grown yet) flags
 * it for removal.
 *
 * <p><b>The fix:</b> wrap the {@code MassData.isInvalid()} call site inside
 * {@code processSubLevelRemovals}. Capture the iteration's {@link ServerSubLevel}
 * via {@code @Local}, and if it's flagged as a kinematic mirror, return
 * {@code false} (pretend the mass tracker is fine). This short-circuits both
 * {@code destroyAllBlocks} (no fountain) and {@code markRemoved} (no respawn
 * cascade). All other sub-levels pass through to the original check.
 *
 * <p><b>Why a wrap operation and not @Inject HEAD cancel:</b> we only want to skip
 * the auto-removal path for kinematic mirrors, not the entire method (other
 * sub-levels in the same container should still be cleaned up normally). Wrapping
 * just the validity check is the most surgical possible intervention.
 *
 * <p>{@code @Pseudo} because we target a Sable class that's only on the runtime
 * classpath. {@code remap = false} because Sable is not obfuscated.
 */
@Pseudo
@Mixin(value = SubLevelContainer.class, remap = false)
public abstract class SableProcessSubLevelRemovalsKinematicSkipMixin {

    @WrapOperation(
        method = "processSubLevelRemovals",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/api/physics/mass/MassData;isInvalid()Z"
        ),
        remap = false,
        require = 0  // best-effort: if Sable refactors the method, fall back to no-op
    )
    private boolean ipl$skipKinematicMirrorAutoRemoval(
        MassData massData,
        Operation<Boolean> original,
        @Local ServerSubLevel iterationSubLevel
    ) {
        if (iterationSubLevel instanceof IplKinematicSubLevelHolder holder
            && holder.ipl$isKinematicMirror()) {
            // Pretend the mass tracker is valid. Skips both the destroyAllBlocks
            // call (no fountain) and the markRemoved call (mirror persists). Our
            // controller is the only thing that should be removing kinematic
            // mirrors.
            return false;
        }
        // Normal sub-level — defer to Sable's check.
        return original.call(massData);
    }
}
