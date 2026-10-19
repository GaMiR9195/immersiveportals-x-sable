package ipl.sable.mixin;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Mutable previous endpoint for atomic coordinate-frame changes on BOTH sides.
 * Sable exposes only Pose3dc here; no public API sets this endpoint independently
 * of logicalPose. Sharing this accessor avoids a duplicate client-only mixin.
 */
@Mixin(value = SubLevel.class, remap = false)
public interface IplSubLevelPreviousPoseAccessor {

    @Accessor("lastPose")
    Pose3d ipl$getPreviousPose();
}
