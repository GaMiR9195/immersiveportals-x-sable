package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ryanhcode.sable.api.physics.mass.MergedMassTracker;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.jetbrains.annotations.Nullable;

/**
 * THE ASSEMBLY OFFSET: compensate the FIRST merged-mass upload's rotation-point jump.
 *
 * <p>{@code MergedMassTracker.uploadData} keeps the world mapping invariant when the center
 * of mass moves: {@code position += R·(CoM − lastCoM)}, then {@code rotationPoint := CoM}.
 * But on the FIRST upload after (re)construction {@code lastCenterOfMass} is null — the
 * code baselines it to the current CoM, computes {@code movement = 0}, and STILL jumps
 * {@code rotationPoint} from whatever assembly left there (the {@code plotAnchor + 0.5}
 * fallback — the merged CoM is never available synchronously during assembly) to the real
 * CoM, WITHOUT the position compensation. Under the pose convention
 * {@code world(p) = R·(p − rotationPoint)·s + position} that shifts the whole ship by
 * {@code R·(rotationPointBefore − CoM)·s}: half a block along the assembler's facing for a
 * two-block ship (assembler + block), growing with structure size, and the same for a
 * swivel-bearing split. A single split block has {@code CoM == rotationPoint} — no shift —
 * matching observation exactly.
 *
 * <p>Fix: detect the first-baseline upload, and if the rotation point actually jumped,
 * apply the missing {@code position += R·(CoM − rotationPointBefore)·s} and re-teleport —
 * making the first upload mapping-invariant like every later one.
 */
@Pseudo
@Mixin(value = MergedMassTracker.class, remap = false)
public abstract class IplMergedMassFirstUploadMixin {

    @Shadow(remap = false)
    @Final
    private ServerSubLevel subLevel;

    @Shadow(remap = false)
    @Nullable
    private Vector3d lastCenterOfMass;

    @Shadow(remap = false)
    @Nullable
    private Vector3d centerOfMass;

    @Unique
    private static long ipl$lastCompLogMs = 0;

    @WrapMethod(method = "uploadData", remap = false)
    private void ipl$compensateFirstBaselineJump(Operation<Void> original) {
        boolean firstBaseline = this.lastCenterOfMass == null && this.centerOfMass != null;
        Vector3d rpBefore = null;
        if (firstBaseline) {
            rpBefore = new Vector3d(this.subLevel.logicalPose().rotationPoint());
        }

        original.call();

        if (!firstBaseline || this.lastCenterOfMass == null || this.centerOfMass == null) {
            return; // upload didn't run (no change) or wasn't the first — nothing to fix
        }

        Pose3d pose = this.subLevel.logicalPose();
        Vector3d jump = new Vector3d(this.centerOfMass).sub(rpBefore);
        if (jump.lengthSquared() < 1.0e-10) {
            return;
        }

        // REHOME GUARD: a hosted twin's first upload jumps the rotation point from the
        // SOURCE plot slot to the NEW one — a plot-grid translation the blocks made too,
        // which must stay uncompensated (the verbatim-copied position is already correct
        // for the new slot). Genuine assembly CoM skew is sub-plot-scale; slot deltas are
        // whole plot spacings. Anything beyond ship scale is a slot move — skip.
        if (Math.abs(jump.x) > 512.0 || Math.abs(jump.y) > 512.0 || Math.abs(jump.z) > 512.0) {
            return;
        }

        // position += R · (CoM − rotationPointBefore) · s — the compensation uploadData
        // skipped because its baseline made movement zero.
        jump.mul(pose.scale().x(), pose.scale().y(), pose.scale().z());
        pose.orientation().transform(jump);
        pose.position().add(jump);

        ServerLevel level = this.subLevel.getLevel();
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container instanceof dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer serverContainer) {
            serverContainer.physicsSystem().getPipeline()
                .teleport(this.subLevel, pose.position(), pose.orientation());
        }

        long now = System.currentTimeMillis();
        if (now - ipl$lastCompLogMs > 1000) {
            ipl$lastCompLogMs = now;
            org.slf4j.LoggerFactory.getLogger("ipl-mass").info(
                "[IPL-MASS] first-upload rotation-point jump compensated for {}: ({}, {}, {})",
                this.subLevel.getUniqueId(),
                String.format("%.3f", jump.x), String.format("%.3f", jump.y), String.format("%.3f", jump.z));
        }
    }
}
