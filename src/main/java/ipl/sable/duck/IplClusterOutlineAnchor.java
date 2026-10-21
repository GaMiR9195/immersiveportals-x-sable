package ipl.sable.duck;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/** Original plot-space anchor; never infer ownership from a camera-relative vertex. */
public interface IplClusterOutlineAnchor {
    @Nullable BlockPos ipl$getOutlineAnchor();
}
