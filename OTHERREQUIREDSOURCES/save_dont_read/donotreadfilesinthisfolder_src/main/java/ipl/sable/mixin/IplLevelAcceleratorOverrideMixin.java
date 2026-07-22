package ipl.sable.mixin;

import dev.ryanhcode.sable.util.LevelAccelerator;
import ipl.sable.transit.IplTerrainReadOverride;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Route {@code LevelAccelerator} chunk reads to {@link IplTerrainReadOverride}'s level while
 * the override is set (only during hosted-terrain pre-enrollment — see
 * {@code IplHostedTicketManagerMixin}). Injected at the head of {@code getChunk(int,int)} so
 * the accelerator's single-entry chunk cache is fully bypassed: no parent-dim chunk can leak
 * into the cache and be served for a later hosting-dim read at the same coordinates.
 */
@Pseudo
@Mixin(value = LevelAccelerator.class, remap = false)
public abstract class IplLevelAcceleratorOverrideMixin {

    @org.spongepowered.asm.mixin.Shadow(remap = false)
    @org.spongepowered.asm.mixin.Final
    private int minBuildHeight;

    @Inject(method = "getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;",
        at = @At("HEAD"), cancellable = true, require = 0)
    private void ipl$readFromOverrideLevel(int chunkX, int chunkZ, CallbackInfoReturnable<LevelChunk> cir) {
        Level override = IplTerrainReadOverride.get();
        if (override != null) {
            net.minecraft.core.BlockPos offset = IplTerrainReadOverride.getOffset();
            if (offset != null) {
                // Approximate chunk translation — content reads are caught per-block below,
                // this just keeps the synchronous chunk loads in the right area.
                cir.setReturnValue(override.getChunk(
                    chunkX + Math.floorDiv(offset.getX(), 16),
                    chunkZ + Math.floorDiv(offset.getZ(), 16)));
            } else {
                cir.setReturnValue(override.getChunk(chunkX, chunkZ));
            }
        }
    }

    /**
     * The accelerator caches the HOSTING dim's height profile at construction and indexes
     * chunk sections as {@code (y >> 4) - hostingMinSection}. An override-provided chunk
     * from a parent with a DIFFERENT height profile (nether: minSection 0 vs hosting -4)
     * gets every read shifted by the profile delta — 64 blocks vertically for the nether.
     * That baked a vertically displaced copy of the parent terrain into the physics scene
     * ("collides with terrain that isn't quite the nether"). While the override is active,
     * read through the chunk's own accessor, which uses its own dimension's profile.
     */
    @Inject(method = "getBlockState(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("HEAD"), cancellable = true, require = 0)
    private void ipl$readBlockStateWithChunkProfile(
        LevelChunk chunk, net.minecraft.core.BlockPos pos,
        CallbackInfoReturnable<net.minecraft.world.level.block.state.BlockState> cir
    ) {
        Level override = IplTerrainReadOverride.get();
        if (override != null) {
            net.minecraft.core.BlockPos offset = IplTerrainReadOverride.getOffset();
            if (offset != null) {
                // Translated read (straddle terrain clone): source-frame P → dest-frame P+offset.
                cir.setReturnValue(override.getBlockState(pos.offset(offset)));
            } else {
                cir.setReturnValue(chunk.getBlockState(pos));
            }
            return;
        }

        // No override, but the chunk's height profile differs from this accelerator's:
        // the chunk arrived from ANOTHER dimension (a hosting-level plot chunk served via
        // the plot bridge to e.g. a nether-profile accelerator during entity collision).
        // Indexing it with this accelerator's minSection shifts every read by the profile
        // delta (64 blocks for nether vs hosting), and the bounds gate is wrong too — so
        // an entity in the nether walking on a hosted ship reads ALL AIR and falls through.
        // Read chunk-native, which uses the chunk's own profile.
        if (chunk.getMinBuildHeight() != this.minBuildHeight) {
            cir.setReturnValue(chunk.getBlockState(pos));
        }
    }
}
