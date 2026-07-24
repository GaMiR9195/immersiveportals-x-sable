package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import ipl.sable.dim.IplWorldFrameContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * THE SERVER IDENTITY FIX — the missing half of the Round-1 client identity fix.
 *
 * <p>{@code getLevel()} on a hosted plot block entity returns the owning sub-level's PARENT
 * {@code ServerLevel} — the dimension the ship actually occupies — instead of the
 * {@code ipl_sable:sublevels} hosting level. Getter only: the {@code level} FIELD, chunk
 * storage, save/tick machinery and vanilla internals (which use the field directly) keep
 * hosting-level semantics, exactly mirroring {@code IplHostedBeLevelIdentityMixin} on the
 * client, where this approach is proven.
 *
 * <p>Why this is the root fix and not a patch: stock Sable keeps a ship's plot chunks in
 * the SAME level the ship occupies, so every mod resolves its per-level machinery from
 * {@code be.getLevel()} — {@code SubLevelContainer.getContainer(getLevel())} (physics
 * system, pipeline, sub-level-by-UUID), {@code WorldAttached} registries (Simulated's
 * {@code ServerLevelRopeManager}, plunger collections), vanilla chunk-tracking packet
 * sinks ({@code chunkMap.getPlayers}), and physics load gates
 * ({@code wouldBeLoaded(getLevel(), object)} whose WORLD-frame chunk checks must run
 * against the dimension that actually has players and terrain). Hosted, the field level is
 * the void — so rope strands registered under the wrong manager bucket and were gated by
 * void chunk state, swivel bearings looked their split part up in the wrong container, and
 * every "connects two points" feature broke identically. With parent identity restored at
 * the getter, all of those resolve stock semantics with zero per-mod code:
 * <ul>
 *   <li>rope strands: physics system, manager bucket, tracking players and the
 *       {@code wouldBeLoaded} gate all resolve the parent — added, simulated, and synced
 *       exactly like stock;</li>
 *   <li>swivel bearing: {@code getContainer(getLevel()).getSubLevel(id)} resolves through
 *       the parent (plus the existing UUID bridge) and the constraint attaches through the
 *       parent pipeline — the scene that actually owns hosted bodies;</li>
 *   <li>springs/others: loaded-area checks and partner lookups run against the parent with
 *       plot-range reads answered by the plot bridge.</li>
 * </ul>
 *
 * <p>Plot-range BLOCK/BE reads and writes against the parent level resolve through Sable's
 * own chunk-cache mixins + {@code IplPlotBridgeMixin} (the plot grid is a universal address
 * space), so code holding a parent identity still reaches the plot content that physically
 * lives in the hosting dimension.
 *
 * <p>Registered in the main mixin list; no-ops client-side (the client mixin owns that
 * side). Hot-path cost for non-hosted BEs: one isClientSide check and one dimension-key
 * identity compare inside {@link IplWorldFrameContext#resolveParentForPlotBe}.
 */
@Mixin(BlockEntity.class)
public abstract class IplHostedBeParentIdentityMixin {

    @Shadow
    @Final
    protected BlockPos worldPosition;

    @ModifyReturnValue(method = "getLevel", at = @At("RETURN"))
    private Level ipl$hostedPlotBeParentIdentity(Level original) {
        if (original == null || original.isClientSide()) {
            return original;
        }
        ServerLevel parent = IplWorldFrameContext.resolveParentForPlotBe(original, this.worldPosition);
        return parent != null ? parent : original;
    }
}
