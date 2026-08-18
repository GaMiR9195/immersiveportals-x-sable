package ipl.sable.mixin;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Mutable previous-pose endpoint on the AUTHORITATIVE side.
 *
 * <p>{@code SubLevel.lastPose()} only exposes a read-only view, and the existing accessor
 * for the same field is registered in the client mixin list, so it is simply absent on a
 * dedicated server. A parent flip has to correct this endpoint server-side -- the render
 * timeline is derived from authoritative poses -- hence a second, commonly registered
 * accessor rather than moving the client one and changing what it applies to.
 */
@Mixin(value = SubLevel.class, remap = false)
public interface IplSubLevelPreviousPoseAccessor {

    @Accessor("lastPose")
    Pose3d ipl$getPreviousPose();
}
