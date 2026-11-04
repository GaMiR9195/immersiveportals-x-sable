package ipl.sable.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.UUID;

/**
 * Create: Pocket Sized. A scale sync that repeats the scale the client already holds does not
 * reset the sub-level's interpolation.
 *
 * <p>Pocket sets {@code snapInterpolation} not only after an instant resize, where the
 * placement really jumps, but also on every Sable full sync and in every reply to its client
 * handshake. Under IP a full sync often continues an object the client already shows: the
 * successor of a cross-dimension move carries the ghost's UUID, and a repeated start-tracking
 * is skipped by {@code StartTrackingPacketMixin}. The snap threw the moving ship forward to its
 * newest buffered snapshot and cleared the buffer, so it stood still until Sable's clock caught
 * up; a ship leaving a portal into the player's dimension did that twice (full sync, then the
 * handshake reply). With the scale unchanged there is nothing to snap; a first sync, before the
 * client knows the scale, keeps Pocket's snap.
 */
@Pseudo
@Mixin(targets = "com.misterblusky9.pym.internal.scale.ScaleState", remap = false)
public abstract class PocketScaleStateMixin {

    /** Relative tolerance of Pocket's {@code ScaleBounds.same}. */
    @Unique
    private static final double IPL_SCALE_EPSILON = 1.0E-6;

    @Shadow
    public static boolean hasClientSnapshot(UUID id) {
        throw new AssertionError();
    }

    @Shadow
    public static double getClientScale(UUID id) {
        throw new AssertionError();
    }

    @Shadow
    public static double getClientTarget(UUID id) {
        throw new AssertionError();
    }

    @ModifyVariable(method = "acceptClientSnapshot", at = @At("HEAD"), argsOnly = true)
    private static boolean ipl$snapOnlyOnScaleChange(
        boolean snap, UUID id, int interpolationTick, double current, double target, boolean snapInterpolation
    ) {
        boolean repeatsKnownScale = hasClientSnapshot(id)
            && ipl$sameScale(getClientScale(id), current)
            && ipl$sameScale(getClientTarget(id), target);
        return snap && !repeatsKnownScale;
    }

    @Unique
    private static boolean ipl$sameScale(double a, double b) {
        return Math.abs(a - b) <= IPL_SCALE_EPSILON * Math.max(Math.abs(a), Math.abs(b));
    }
}
