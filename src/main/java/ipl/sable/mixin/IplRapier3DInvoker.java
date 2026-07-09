package ipl.sable.mixin;

import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Sable 2.0 made most {@code Rapier3D} natives package-private (and scene ids became
 * native {@code long} handles). The straddle clone driver ({@code IplStraddleCloneBody})
 * manages a second native body outside any Java pipeline, so it needs direct access —
 * static invokers merge into the target and carry package-private access with them.
 * (A same-package bridge class would be a JPMS split package; NeoForge forbids those.)
 */
@Pseudo
@Mixin(value = Rapier3D.class, remap = false)
public interface IplRapier3DInvoker {

    @Invoker(value = "createSubLevel", remap = false)
    static void ipl$createSubLevel(long sceneHandle, int id, double[] pose) {
        throw new AssertionError();
    }

    @Invoker(value = "removeSubLevel", remap = false)
    static void ipl$removeSubLevel(long sceneHandle, int id) {
        throw new AssertionError();
    }

    @Invoker(value = "setCenterOfMass", remap = false)
    static void ipl$setCenterOfMass(long sceneHandle, int id, double x, double y, double z) {
        throw new AssertionError();
    }

    @Invoker(value = "setLocalBounds", remap = false)
    static void ipl$setLocalBounds(
        long sceneHandle, int id, int minX, int minY, int minZ, int maxX, int maxY, int maxZ
    ) {
        throw new AssertionError();
    }

    @Invoker(value = "setMassPropertiesFrom", remap = false)
    static void ipl$setMassPropertiesFrom(long sceneHandle, int id, MassData massTracker) {
        throw new AssertionError();
    }

    @Invoker(value = "addChunk", remap = false)
    static void ipl$addChunk(
        long sceneHandle, int x, int y, int z, int[] chunk, boolean global, int id
    ) {
        throw new AssertionError();
    }

    @Invoker(value = "removeChunk", remap = false)
    static void ipl$removeChunk(long sceneHandle, int x, int y, int z, boolean global) {
        throw new AssertionError();
    }

    @Invoker(value = "teleportObject", remap = false)
    static void ipl$teleportObject(
        long sceneHandle, int id, double x, double y, double z,
        double i, double j, double k, double r
    ) {
        throw new AssertionError();
    }

    @Invoker(value = "getLinearVelocity", remap = false)
    static void ipl$getLinearVelocity(long sceneHandle, int bodyId, double[] store) {
        throw new AssertionError();
    }

    @Invoker(value = "getAngularVelocity", remap = false)
    static void ipl$getAngularVelocity(long sceneHandle, int bodyId, double[] store) {
        throw new AssertionError();
    }
}
