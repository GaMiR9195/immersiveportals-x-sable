package ipl.sable.mixin;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import ipl.sable.iface.IplKinematicSubLevelHolder;
import ipl.sable.transit.MirrorRemovalGuard;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Recover persisted mirrors on load.
 *
 * <p>{@link SableHoldingChunkMapMirrorSaveSkipMixin} prevents new saves from
 * persisting mirrors, but legacy saves made before that fix landed -- including
 * the one in the user's hang log -- still contain ghost mirror sub-levels. On
 * reload, {@link SubLevelSerializer#fullyLoad} resurrects them as normal
 * airships (the {@code @Unique} kinematic flag is gone, the registry entry was
 * never persisted), and the transit controller treats them as candidates, spawning
 * a mirror for the ghost. That mirror gets a mirror, and so on -- the cascade.
 *
 * <p>{@link ipl.sable.transit.MirrorOps#spawnMirror} writes
 * {@code "ipl_mirror": true} into the mirror's {@code user_data}, which DOES
 * ride through Sable's serialise/deserialise. We detect that tag at the RETURN
 * of {@code fullyLoad}, re-arm the kinematic flag (so the auto-removal
 * skip-mixin protects it from the {@code destroyAllBlocks} fountain in the brief
 * window before our removal lands), and call {@code removeSubLevel(REMOVED)} via
 * the authorised guard -- the same idempotent teardown
 * {@link ipl.sable.transit.MirrorOps#despawnMirror} uses. Then we
 * {@code setReturnValue(null)} so Sable's load loop treats it as a failed load
 * and moves on.
 *
 * <p><b>Why re-arm the kinematic flag + authorise the guard:</b> without the
 * flag, {@code processSubLevelRemovals}' mass-validity check could fire on the
 * ghost (whose mass tracker may be stale post-load) and run
 * {@code plot.destroyAllBlocks()}, raining mirror blocks as items in the dest
 * dim. With the flag, the skip mixin makes that check return false; with the
 * guard authorised, the removal itself isn't cancelled by
 * {@link SableMirrorRemovalGuardMixin}.
 */
@Pseudo
@Mixin(value = SubLevelSerializer.class, remap = false)
public abstract class SableSubLevelSerializerMirrorRecoveryMixin {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-mirror-recovery");

    @Inject(
        method = "fullyLoad",
        at = @At("RETURN"),
        remap = false,
        require = 0,
        cancellable = true
    )
    private static void ipl$recoverPersistedMirror(
        ServerLevel level,
        SubLevelData halfLoaded,
        CallbackInfoReturnable<ServerSubLevel> cir
    ) {
        ServerSubLevel sub = cir.getReturnValue();
        if (sub == null) return;

        CompoundTag userData = sub.getUserDataTag();
        if (userData == null) return;
        if (!userData.getBoolean("ipl_mirror")) return;

        LOG.warn(
            "[IPL-MIRROR-RECOVERY] discarded persisted mirror uuid={} from {} -- "
                + "ghost from pre-fix save; this load won't cascade",
            sub.getUniqueId(),
            level.dimension().location()
        );

        // Re-arm the kinematic flag so the mass-data skip mixin protects the
        // ghost from auto-fountain in the window before the queued removal is
        // processed.
        if (sub instanceof IplKinematicSubLevelHolder holder) {
            holder.ipl$setKinematicMirror(true);
        }

        try {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container != null) {
                MirrorRemovalGuard.inAuthorizedRemoval = true;
                try {
                    container.removeSubLevel(sub, SubLevelRemovalReason.REMOVED);
                } finally {
                    MirrorRemovalGuard.inAuthorizedRemoval = false;
                }
            }
        } catch (Throwable t) {
            LOG.error("[IPL-MIRROR-RECOVERY] failed to remove ghost mirror uuid={}",
                sub.getUniqueId(), t);
        }

        // Tell Sable's load loop the load "failed" -- it logs and moves on,
        // which is exactly what we want for a discarded ghost.
        cir.setReturnValue(null);
    }
}
