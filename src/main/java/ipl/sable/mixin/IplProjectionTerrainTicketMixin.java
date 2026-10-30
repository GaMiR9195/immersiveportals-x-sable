package ipl.sable.mixin;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicket;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager;
import ipl.sable.transit.IplAtlasStraddleSession;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Destination terrain for projections (problem: "the atlas uses the SOURCE ground").
 *
 * <p>Sable uploads terrain sections into a level's physics scene only around the
 * sub-levels and arbitrary objects that LIVE in that level. An Atlas image lives in the
 * destination chart while its body stays native to the source level, so nothing ever
 * asked for the destination ground under the image: the part of a ship that went through
 * a cross-dimension portal had nothing to stand on (and the source floor behind the
 * entry portal was the only floor the body still knew). Same-dimension portals were
 * mostly fine only because the exit usually lies inside the body's own ticket radius.
 *
 * <p>At the end of each ticket update we add tickets for the mapped bounds of every live
 * projection INTO this level, through the manager's own {@code addTicket} (same
 * bookkeeping and 20-tick expiry as Sable's tickets). Only chunks that are already
 * loaded enough are used -- this never forces a chunk load from the physics tick.
 */
@Mixin(value = PhysicsChunkTicketManager.class, remap = false)
public abstract class IplProjectionTerrainTicketMixin {

    @Shadow
    private PhysicsChunkTicket addTicket(Level level, PhysicsPipeline pipeline, SectionPos sectionPos,
                                         int x, int y, int z, int index, long gameTime) {
        throw new AssertionError();
    }

    @Inject(method = "update", at = @At("TAIL"))
    private void ipl$ticketProjectionTerrain(ServerLevel level, ServerSubLevelContainer container,
                                             SubLevelPhysicsSystem system, PhysicsPipeline pipeline,
                                             double timeStep, CallbackInfo ci) {
        if (IplAtlasStraddleSession.isIdle()) return;
        final long gameTime = level.getGameTime();
        IplAtlasStraddleSession.forEachSessionInto(level, (sub, mapping) -> {
            BoundingBox3d mapped = mapping.mapAabb(sub.boundingBox());
            // Sable pads its own tickets by 1 block plus a velocity prediction; a fixed
            // 2-block pad covers one fast tick of motion of the image as well.
            mapped.expand(2.0, mapped);
            int minX = Mth.floor(mapped.minX()) >> 4, maxX = Mth.floor(mapped.maxX()) >> 4;
            int minY = Mth.floor(mapped.minY()) >> 4, maxY = Mth.floor(mapped.maxY()) >> 4;
            int minZ = Mth.floor(mapped.minZ()) >> 4, maxZ = Mth.floor(mapped.maxZ()) >> 4;
            // A pathological mapping (huge body / bad portal) must not stall the tick.
            if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) * (maxY - minY + 1) > 4096L) return;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!PhysicsChunkTicketManager.isChunkLoadedEnough(level, x, z)) continue;
                    for (int y = minY; y <= maxY; y++) {
                        int index = level.getSectionIndexFromSectionY(y);
                        if (index < 0 || index >= level.getSectionsCount()) continue;
                        this.addTicket(level, pipeline, SectionPos.of(x, y, z), x, y, z, index, gameTime);
                    }
                }
            }
        });
    }
}
