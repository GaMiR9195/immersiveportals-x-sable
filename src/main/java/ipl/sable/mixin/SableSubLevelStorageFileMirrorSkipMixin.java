package ipl.sable.mixin;

import dev.ryanhcode.sable.sublevel.storage.region.SubLevelStorageFile;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The single, bypass-proof chokepoint that keeps kinematic mirrors off disk.
 *
 * <p><b>Why a chokepoint instead of patching save paths:</b> Sable serialises
 * sub-levels through several entry points -- {@code SubLevelHoldingChunkMap.saveAll},
 * {@code processUnload} (chunk-unload), and {@code moveToUnloaded} (physics-ticket
 * unload). We previously patched them individually and still missed
 * {@code processUnload}, which leaked a mirror to disk and produced the
 * "Plot already exists at 0, 0" crash on the next load (two sub-levels claiming
 * one plot). Every one of those paths ultimately calls
 * {@link SubLevelStorageFile#write(int, CompoundTag)} to commit a sub-level's NBT.
 * Gating that one method covers them all -- and any future path we haven't seen.
 *
 * <p><b>How:</b> a mirror is tagged at spawn with {@code user_data.ipl_mirror = true}
 * (see {@code MirrorOps.spawnMirror}); that tag rides inside the serialised NBT. When
 * {@code write} is handed a tag carrying it, we replace the tag with {@code null}.
 * Sable's own {@code write} treats a null tag as "clear this slot" -- so we not only
 * skip persisting the mirror but also free any stale mirror bytes left from a previous
 * overwrite. The caller still gets a valid pointer, so no save-failure toast fires; a
 * later load of that (now empty) slot simply returns null and is skipped gracefully.
 *
 * <p>This is the load-bearing fix; the {@code saveAll} filter, {@code moveToUnloaded}
 * skip, and load-time recovery mixin remain as defense-in-depth.
 */
@Pseudo
@Mixin(value = SubLevelStorageFile.class, remap = false)
public abstract class SableSubLevelStorageFileMirrorSkipMixin {

    @ModifyVariable(
        method = "write(ILnet/minecraft/nbt/CompoundTag;)V",
        at = @At("HEAD"),
        argsOnly = true,
        remap = false,
        require = 0
    )
    private CompoundTag ipl$skipMirrorWrite(CompoundTag tag) {
        // getCompound returns an empty tag (not null) when "user_data" is absent,
        // and getBoolean returns false for a missing key -- so this is null-safe
        // and false for every non-mirror sub-level.
        if (tag != null && tag.getCompound("user_data").getBoolean("ipl_mirror")) {
            return null; // routes to SubLevelStorageFile.clear(index): never persist
        }
        return tag;
    }
}
