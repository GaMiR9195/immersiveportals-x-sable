package ipl.sable.mixin;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;
import ipl.sable.iface.IplKinematicSubLevelHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;

/**
 * Don't persist kinematic mirrors.
 *
 * <p><b>The bug:</b> mirrors are server-side transient previews. Their identity
 * lives entirely in memory -- the {@link ipl.sable.transit.MirrorRegistry} is a
 * static {@link java.util.HashMap}, and the kinematic flag is a {@code @Unique}
 * field that defaults to {@code false}. Nothing about "this is a mirror"
 * survives a save/load cycle. If Sable serialises a mirror, the next load
 * brings back an orphan airship that the controller treats as a normal candidate
 * -- spawning a mirror for it, which gets persisted, spawning another, and so
 * on. The user's hang log (29May 20:55) captured exactly this cascade: three
 * mirrors spawned in 8 seconds with TPS dropping from ~10 to a frozen server
 * thread.
 *
 * <p><b>The fix:</b> when {@code SubLevelHoldingChunkMap.saveAll} pulls the
 * container's sub-level list to serialise, redirect the call to return a
 * filtered copy that excludes kinematic mirrors. The mirror stays alive in
 * memory (no flicker, no respawn cost on autosave) -- it just doesn't touch
 * disk.
 *
 * <p><b>Why @Redirect and not @Inject HEAD despawn:</b> despawning at HEAD
 * would remove every mirror on every autosave, causing a brief visual flicker
 * before the controller respawns next tick. Filtering the iteration source
 * keeps the in-memory mirror untouched.
 *
 * <p>This is one of three layers:
 * <ul>
 *   <li>{@link SableMirrorSkipMoveToUnloadedMixin} -- already covers the
 *       chunk-unload persistence path.</li>
 *   <li>This mixin -- covers the periodic + shutdown {@code saveAll}.</li>
 *   <li>{@code SableSubLevelSerializerMirrorRecoveryMixin} -- discards any
 *       tagged mirror that does come back from a legacy save.</li>
 * </ul>
 */
@Pseudo
@Mixin(value = SubLevelHoldingChunkMap.class, remap = false)
public abstract class SableHoldingChunkMapMirrorSaveSkipMixin {

    @Redirect(
        method = "saveAll",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/api/sublevel/ServerSubLevelContainer;getAllSubLevels()Ljava/util/List;"
        ),
        remap = false,
        require = 0
    )
    private List<ServerSubLevel> ipl$filterMirrorsFromSave(ServerSubLevelContainer container) {
        List<ServerSubLevel> all = container.getAllSubLevels();
        List<ServerSubLevel> filtered = new ArrayList<>(all.size());
        for (ServerSubLevel sub : all) {
            if (sub instanceof IplKinematicSubLevelHolder holder
                && holder.ipl$isKinematicMirror()) {
                // Transient runtime preview -- never written to disk. The
                // mirror stays alive in the container for in-memory rendering;
                // it just doesn't show up in Sable's iteration this save.
                continue;
            }
            filtered.add(sub);
        }
        return filtered;
    }
}
