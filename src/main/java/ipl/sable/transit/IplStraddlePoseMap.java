package ipl.sable.transit;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.q_misc_util.my_util.DQuaternion;

/**
 * Frame mapping for straddling hosted sub-levels: from a given context dimension, a ship
 * straddling INTO that dimension has a portal-mapped pose (translation-only portal pairs —
 * the pose translates by the portal offset; orientation is unchanged).
 *
 * <p>Server side the offset comes from the active {@link IplStraddleTerrainClone} session;
 * client side it is derived from the straddle portal found by
 * {@code SourceClipPortalFinder} (lazily, via the client-only lookup class).
 */
public final class IplStraddlePoseMap {

    private IplStraddlePoseMap() {}

    /**
     * The block offset mapping {@code sub}'s source frame into {@code contextLevel}, or null
     * when {@code sub} isn't straddling into that dimension (or the portal isn't a
     * translation-only pair).
     */
    @Nullable
    public static BlockPos getOffsetInto(@Nullable SubLevel sub, @Nullable Level contextLevel) {
        if (sub == null || contextLevel == null || !IplDimAgnostic.isEnabled()) return null;
        if (!IplDimAgnostic.isHosted(sub)) return null;
        if (IplDimAgnostic.getParentLevel(sub) == contextLevel) return null; // native frame

        if (contextLevel.isClientSide()) {
            // Client-only class; loaded lazily when this branch executes.
            return ipl.sable.client.IplClientHostedLookup.getClientStraddleOffsetInto(sub, contextLevel);
        }
        return IplStraddleTerrainClone.getOffsetInto(sub, contextLevel);
    }

    /**
     * Whether {@code sub} is currently straddling a portal at all, judged from
     * {@code contextLevel}'s side. Client: the straddle-portal finder (same source the
     * renderer and collision mapping use). Server: an active terrain-clone session.
     */
    public static boolean isStraddling(@Nullable SubLevel sub, @Nullable Level contextLevel) {
        if (sub == null || contextLevel == null || !IplDimAgnostic.isEnabled()) return false;
        if (!IplDimAgnostic.isHosted(sub)) return false;
        if (contextLevel.isClientSide()) {
            return ipl.sable.client.IplClientHostedLookup.isClientStraddling(sub);
        }
        return IplStraddleTerrainClone.hasSession(sub.getUniqueId());
    }

    /** Copy of {@code pose} translated into the mapped frame. */
    public static Pose3d mapped(Pose3dc pose, BlockPos offset) {
        Pose3d out = new Pose3d(pose);
        out.position().add(offset.getX(), offset.getY(), offset.getZ());
        return out;
    }

    public static boolean isApproxIdentity(@Nullable DQuaternion q) {
        if (q == null) return true;
        return Math.abs(Math.abs(q.w) - 1.0) < 1e-4
            && Math.abs(q.x) < 1e-4 && Math.abs(q.y) < 1e-4 && Math.abs(q.z) < 1e-4;
    }

    @Nullable
    public static BlockPos blockAligned(Vec3 d) {
        long rx = Math.round(d.x), ry = Math.round(d.y), rz = Math.round(d.z);
        if (Math.abs(d.x - rx) > 0.01 || Math.abs(d.y - ry) > 0.01 || Math.abs(d.z - rz) > 0.01) {
            return null;
        }
        return new BlockPos((int) rx, (int) ry, (int) rz);
    }
}
