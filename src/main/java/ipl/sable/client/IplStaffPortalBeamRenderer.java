package ipl.sable.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler;
import ipl.sable.mixin.client.IplPhysicsStaffBeamAccessorMixin;
import ipl.sable.mixin.client.IplPhysicsStaffBeamInvokerMixin;
import ipl.sable.mixin.client.IplPhysicsStaffClientHandlerAccessorMixin;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.DefaultSuperRenderTypeBuffer;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.portal.Portal;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;

import java.util.List;
import java.util.UUID;

/** Draws one real beam section in each IP world render, never a main-world projected shortcut. */
public final class IplStaffPortalBeamRenderer {

    /** Suppresses Simulated's main-world-only draw while this renderer owns the recursive pass. */
    private static final ThreadLocal<Boolean> PHYSICAL_BEAM_PASS = ThreadLocal.withInitial(() -> false);

    private IplStaffPortalBeamRenderer() {}

    public static void render(PoseStack poseStack, Camera camera) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.level == null) return;

        PhysicsStaffClientHandler handler =
            dev.simulated_team.simulated.SimulatedClient.PHYSICS_STAFF_CLIENT_HANDLER;
        Object2ObjectMap<UUID, Object> beams = ((IplPhysicsStaffClientHandlerAccessorMixin) (Object) handler)
            .ipl$getBeams();
        if (beams.isEmpty()) return;

        float partialTick = AnimationTickHolder.getPartialTicks();
        SuperRenderTypeBuffer buffer = DefaultSuperRenderTypeBuffer.getInstance();
        boolean drew = false;

        for (Object2ObjectMap.Entry<UUID, Object> entry : beams.object2ObjectEntrySet()) {
            if (!(entry.getValue() instanceof PhysicsStaffClientHandler.PhysicsBeam beam)) continue;
            Player owner = findPlayer(entry.getKey());
            if (owner == null) continue;

            IplPhysicsStaffBeamAccessorMixin access =
                (IplPhysicsStaffBeamAccessorMixin) (Object) beam;
            // Simulated updates these in its client tick, before IP installs a portal camera.
            // Recomputing getStaffFocusPos during a recursive LevelRenderer pass combines a
            // virtual destination camera with the owner's root-world position and makes the
            // beam pivot/rotate with the camera.
            Vec3 source = access.ipl$getPreviousStart().lerp(access.ipl$getStart(), partialTick);
            Vec3 localAnchor = access.ipl$getPreviousEnd().lerp(access.ipl$getEnd(), partialTick);
            ClientSubLevel sub = ipl$findHostedSubLevel(localAnchor);
            if (sub == null) continue;

            List<Portal> route = IplStraddleStaffPick.getBeamPortalRoute(
                owner.level(), source, sub, localAnchor
            );
            IplStraddleStaffPick.BeamSegment segment =
                IplStraddleStaffPick.getPhysicalBeamSegment(
                    owner.level(), source, sub, localAnchor, partialTick, route
                );

            if (segment == null) continue;

            renderPhysicalBeam(beam, segment, poseStack, buffer, camera.getPosition(), partialTick);
            drew = true;
        }

        if (drew) buffer.draw();
    }

    public static boolean isPhysicalBeamPass() {
        return PHYSICAL_BEAM_PASS.get();
    }

    private static void renderPhysicalBeam(
        PhysicsStaffClientHandler.PhysicsBeam beam, IplStraddleStaffPick.BeamSegment segment,
        PoseStack poseStack, SuperRenderTypeBuffer buffer, Vec3 camera, float partialTick
    ) {
        boolean previous = PHYSICAL_BEAM_PASS.get();
        PHYSICAL_BEAM_PASS.set(true);
        try {
            ((IplPhysicsStaffBeamInvokerMixin) (Object) beam).ipl$render(
                segment.start(), segment.end(), poseStack, buffer, camera, partialTick
            );
        } finally {
            PHYSICAL_BEAM_PASS.set(previous);
        }
    }

    private static Player findPlayer(UUID id) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.level == null ? null : minecraft.level.getPlayerByUUID(id);
        if (player != null) return player;
        for (ClientLevel world : ClientWorldLoader.getClientWorlds()) {
            player = world.getPlayerByUUID(id);
            if (player != null) return player;
        }
        return null;
    }

    /** Beam anchors are plot coordinates, so resolve through hosting container, never main world. */
    private static ClientSubLevel ipl$findHostedSubLevel(Vec3 localAnchor) {
        SubLevelContainer container = IplClientHostedLookup.getHostingContainerOrNull();
        if (container == null) return null;
        ChunkPos chunk = new ChunkPos(BlockPos.containing(localAnchor));
        dev.ryanhcode.sable.sublevel.plot.LevelPlot plot = container.getPlot(chunk);
        SubLevel sub = plot == null ? null : plot.getSubLevel();
        return sub instanceof ClientSubLevel clientSub ? clientSub : null;
    }
}
