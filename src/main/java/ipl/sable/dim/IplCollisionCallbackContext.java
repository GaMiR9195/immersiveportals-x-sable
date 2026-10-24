package ipl.sable.dim;

import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3d;

/** Contact ownership, not whichever Atlas chart happened to submit the fused step last. */
public final class IplCollisionCallbackContext {
    private static final ThreadLocal<ServerLevel> LEVEL = new ThreadLocal<>();
    private IplCollisionCallbackContext() {}
    public static ServerLevel current() { return LEVEL.get(); }

    public static BlockSubLevelCollisionCallback wrap(BlockSubLevelCollisionCallback callback) {
        if (callback == null) return null;
        return new BlockSubLevelCollisionCallback() {
            @Override public double[] onCollision(int x, int y, int z, int ox, int oy, int oz,
                    double hx, double hy, double hz, double velocity, boolean hasOther) {
                return inContact(new BlockPos(x,y,z), hasOther ? new BlockPos(ox,oy,oz) : null,
                    () -> callback.onCollision(x,y,z,ox,oy,oz,hx,hy,hz,velocity,hasOther));
            }
            @Override public CollisionResult sable$onCollision(BlockPos pos, BlockPos other,
                    Vector3d hit, double velocity) {
                return inContact(pos, other, () -> callback.sable$onCollision(pos,other,hit,velocity));
            }
        };
    }
    private static <T> T inContact(BlockPos pos, BlockPos other, java.util.function.Supplier<T> action) {
        var stepping = SubLevelPhysicsSystem.getCurrentlySteppingSystem();
        var source = stepping.getLevel();
        var hosting = IplDimAgnostic.getHostingContainerFor(source);
        if (hosting == null || !(hosting.getLevel() instanceof ServerLevel storage)) return action.get();
        ServerLevel parent = IplWorldFrameContext.resolveParentForPlotBe(storage,pos);
        ServerLevel owner = parent == null ? null : storage;
        if (parent == null && other != null) {
            parent = IplWorldFrameContext.resolveParentForPlotBe(storage,other);
            owner = parent; // this block is terrain; the OTHER contact is plot-local
        }
        if (owner == null) return action.get();
        ServerLevel previous = LEVEL.get();
        ServerLevel previousParent = IplWorldFrameContext.push(parent);
        LEVEL.set(owner);
        try { return action.get(); }
        finally {
            if (previous == null) LEVEL.remove(); else LEVEL.set(previous);
            IplWorldFrameContext.pop(previousParent);
        }
    }
}
