package ipl.sable.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItem;
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
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Draws the staff beam per IP world pass from one shared {@link IplStaffBeamRoutes.Route}.
 *
 * <p>Emission rules (the anti-flicker contract):
 * <ul>
 *   <li><b>Main pass draws every segment lying in the main world.</b> The near segment
 *       (staff → first aperture) therefore renders whether or not the portal is on screen,
 *       and a same-dimension far segment renders at the exit aperture even when the player
 *       is not looking through the portal — both endpoints exist in this world.</li>
 *   <li><b>Each portal pass draws the segment whose traversed-portal prefix matches IP's
 *       active render path</b> (by portal UUID, so a session-store surrogate still matches
 *       the live entity), giving the through-the-aperture view its correctly framed
 *       piece.</li>
 *   <li><b>The staff focus position is sampled only on the main pass</b> and cached per
 *       owner. Portal passes reuse the cached tip: recomputing it there mixes the virtual
 *       portal camera with root-world player state and made the beam pivot with the
 *       camera (the old flicker/pivot bug).</li>
 * </ul>
 */
public final class IplStaffPortalBeamRenderer {

    /** Suppresses Simulated's main-world-only draw while this renderer owns the recursive pass. */
    private static final ThreadLocal<Boolean> PHYSICAL_BEAM_PASS = ThreadLocal.withInitial(() -> false);

    private static final ThreadLocal<IplStaffBeamRoutes.Segment> ACTIVE_SEGMENT =
        new ThreadLocal<>();

    /** Per-owner staff tip sampled on the main pass; reused verbatim by portal passes. */
    private static final Map<UUID, Vec3> FOCUS = new HashMap<>();

    private IplStaffPortalBeamRenderer() {}

    public static void render(PoseStack poseStack, Camera camera, ClientLevel renderLevel) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.level == null || renderLevel == null) return;

        PhysicsStaffClientHandler handler =
            dev.simulated_team.simulated.SimulatedClient.PHYSICS_STAFF_CLIENT_HANDLER;
        Object2ObjectMap<UUID, Object> beams = ((IplPhysicsStaffClientHandlerAccessorMixin) (Object) handler)
            .ipl$getBeams();
        if (beams.isEmpty()) {
            FOCUS.clear();
            return;
        }
        FOCUS.keySet().retainAll(beams.keySet());

        boolean mainPass = !PortalRendering.isRendering();
        List<Portal> renderPath = mainPass ? List.of() : PortalRendering.getPortalPath();
        float partialTick = AnimationTickHolder.getPartialTicks();
        SuperRenderTypeBuffer buffer = DefaultSuperRenderTypeBuffer.getInstance();
        boolean drew = false;

        for (Object2ObjectMap.Entry<UUID, Object> entry : beams.object2ObjectEntrySet()) {
            if (!(entry.getValue() instanceof PhysicsStaffClientHandler.PhysicsBeam beam)) continue;
            Player owner = findPlayer(entry.getKey());
            if (owner == null) continue;

            IplPhysicsStaffBeamAccessorMixin access =
                (IplPhysicsStaffBeamAccessorMixin) (Object) beam;
            Vec3 localAnchor = access.ipl$getPreviousEnd().lerp(access.ipl$getEnd(), partialTick);
            ClientSubLevel sub = ipl$findHostedSubLevel(localAnchor);
            if (sub == null) continue;

            Vec3 staffStart = staffStart(owner, entry.getKey(), beam, mainPass, partialTick);

            IplStaffBeamRoutes.Route route = IplStaffBeamRoutes.resolve(
                entry.getKey(), owner.level(), staffStart, sub, localAnchor, partialTick
            );
            if (route == null) continue;

            for (IplStaffBeamRoutes.Segment segment : IplStaffBeamRoutes.segments(route)) {
                if (!shouldDrawInThisPass(segment, renderLevel, mainPass, renderPath)) continue;
                renderPhysicalBeam(beam, segment, poseStack, buffer, camera.getPosition(), partialTick);
                drew = true;
            }
        }

        if (drew) buffer.draw();
    }

    /**
     * The staff tip for this pass. Sampled fresh on the main pass — where the real camera is
     * active — and cached; portal passes replay the cached tip so route geometry is identical
     * across all passes of a frame. Falls back to the beam's interpolated network start when
     * a portal pass runs before any main pass produced a sample.
     */
    private static Vec3 staffStart(
        Player owner, UUID ownerId, PhysicsStaffClientHandler.PhysicsBeam beam,
        boolean mainPass, float partialTick
    ) {
        if (mainPass) {
            boolean mainHand = owner.getMainHandItem().getItem() instanceof PhysicsStaffItem
                || !(owner.getOffhandItem().getItem() instanceof PhysicsStaffItem);
            Vec3 fresh = PhysicsStaffClientHandler.getStaffFocusPos(owner, mainHand, partialTick);
            FOCUS.put(ownerId, fresh);
            return fresh;
        }
        Vec3 cached = FOCUS.get(ownerId);
        if (cached != null) return cached;
        IplPhysicsStaffBeamAccessorMixin access = (IplPhysicsStaffBeamAccessorMixin) (Object) beam;
        return access.ipl$getPreviousStart().lerp(access.ipl$getStart(), partialTick);
    }

    /** Main pass: everything located in this world. Portal pass: the matching-prefix piece. */
    private static boolean shouldDrawInThisPass(
        IplStaffBeamRoutes.Segment segment, ClientLevel renderLevel,
        boolean mainPass, List<Portal> renderPath
    ) {
        if (segment.world() != renderLevel) return false;
        if (mainPass) return true;
        List<Portal> prefix = segment.prefix();
        if (renderPath.size() != prefix.size()) return false;
        for (int i = 0; i < prefix.size(); i++) {
            if (!renderPath.get(i).getUUID().equals(prefix.get(i).getUUID())) return false;
        }
        return true;
    }

    public static boolean isPhysicalBeamPass() {
        return PHYSICAL_BEAM_PASS.get();
    }

    public static IplStaffBeamRoutes.Segment getActiveSegment() {
        return ACTIVE_SEGMENT.get();
    }

    private static void renderPhysicalBeam(
        PhysicsStaffClientHandler.PhysicsBeam beam, IplStaffBeamRoutes.Segment segment,
        PoseStack poseStack, SuperRenderTypeBuffer buffer, Vec3 camera, float partialTick
    ) {
        boolean previous = PHYSICAL_BEAM_PASS.get();
        PHYSICAL_BEAM_PASS.set(true);
        ACTIVE_SEGMENT.set(segment);
        try {
            ((IplPhysicsStaffBeamInvokerMixin) (Object) beam).ipl$render(
                segment.start(), segment.end(), poseStack, buffer, camera, partialTick
            );
        } finally {
            ACTIVE_SEGMENT.remove();
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
