package ipl.sable.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItem;
import ipl.sable.mixin.client.IplPhysicsStaffBeamAccessorMixin;
import ipl.sable.mixin.client.IplPhysicsStaffBeamInvokerMixin;
import ipl.sable.mixin.client.IplPhysicsStaffClientHandlerAccessorMixin;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import java.util.List;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;

import java.util.HashMap;
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
 *   <li><b>Each portal pass draws every segment physically in its rendered world.</b>
 *       IP's stencil and destination clip decide whether it is visible through that
 *       aperture. This makes a beam a real world object from either portal face and
 *       through recursive portal renders, rather than only along its own traversal path.</li>
 *   <li><b>Geometry is sampled once per frame</b>, even if a portal draws first.
 *       Focus uses the physical owner's camera, never a virtual portal camera;
 *       every subsequent pass replays that same route.</li>
 * </ul>
 */
public final class IplStaffPortalBeamRenderer {

    /** Suppresses Simulated's main-world-only draw while this renderer owns the recursive pass. */
    private static final ThreadLocal<Boolean> PHYSICAL_BEAM_PASS = ThreadLocal.withInitial(() -> false);

    private static final ThreadLocal<IplStaffBeamRoutes.Run> ACTIVE_RUN =
        new ThreadLocal<>();

    private record FrameRuns(Object beam, int frame, net.minecraft.world.level.Level level,
                             List<IplStaffBeamRoutes.Run> runs) {}
    // One geometry snapshot per owner per frame, including when a nested pass draws FIRST.
    private static final Map<UUID, FrameRuns> FRAME_RUNS = new HashMap<>();

    // Never flush the singleton: it can contain another portal pass's selection.
    private static IplOutlineBuffer beamBuffer;

    private IplStaffPortalBeamRenderer() {}

    public static void render(PoseStack poseStack, Camera camera, ClientLevel renderLevel) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.level == null || renderLevel == null) return;

        PhysicsStaffClientHandler handler =
            dev.simulated_team.simulated.SimulatedClient.PHYSICS_STAFF_CLIENT_HANDLER;
        Object2ObjectMap<UUID, Object> beams = ((IplPhysicsStaffClientHandlerAccessorMixin) (Object) handler)
            .ipl$getBeams();
        if (beams.isEmpty()) {
            clear();
            return;
        }
        FRAME_RUNS.keySet().retainAll(beams.keySet());

        // IP reuses one LevelRenderer across recursive worlds. Its `level` field remains
        // the root level, so portal-pass geometry must select WorldRenderInfo.world instead.
        ClientLevel activeLevel = WorldRenderInfo.isRendering()
            ? WorldRenderInfo.getTopRenderInfo().world : renderLevel;
        float partialTick = AnimationTickHolder.getPartialTicks();
        if (beamBuffer == null) beamBuffer = new IplOutlineBuffer();
        SuperRenderTypeBuffer buffer = beamBuffer;
        boolean drew = false;

        for (Object2ObjectMap.Entry<UUID, Object> entry : beams.object2ObjectEntrySet()) {
            if (!(entry.getValue() instanceof PhysicsStaffClientHandler.PhysicsBeam beam)) continue;
            Player owner = findPlayer(entry.getKey());
            if (owner == null) continue;

            IplPhysicsStaffBeamAccessorMixin access =
                (IplPhysicsStaffBeamAccessorMixin) (Object) beam;
            Vec3 localAnchor = access.ipl$getPreviousEnd().lerp(access.ipl$getEnd(), partialTick);
            ClientSubLevel sub = findHostedSubLevel(localAnchor);
            if (sub == null) continue;

            FrameRuns cached = FRAME_RUNS.get(entry.getKey());
            Vec3 staffStart = staffFocus(owner, partialTick);
            if (staffStart == null) continue;
            if (cached == null || cached.beam() != beam || cached.frame() != RenderStates.frameIndex
                || cached.level() != owner.level()) {
                IplStaffBeamRoutes.registerBeamOwner(beam, entry.getKey());
                IplStaffBeamRoutes.Route route = IplStaffBeamRoutes.resolve(
                    entry.getKey(), owner.level(), staffStart, sub, localAnchor, partialTick);
                if (route == null) continue;
                route = IplStaffBeamRoutes.advancePastStaffTip(route, owner.getEyePosition(partialTick));
                cached = new FrameRuns(beam, RenderStates.frameIndex, owner.level(),
                    IplStaffBeamRoutes.runs(route, access.ipl$getNodes().size()));
                FRAME_RUNS.put(entry.getKey(), cached);
            }
            for (IplStaffBeamRoutes.Run run : cached.runs()) {
                if (!shouldDrawInThisPass(run, activeLevel)) continue;
                renderPhysicalBeam(beam, run, poseStack, buffer, camera.getPosition(), partialTick);
                drew = true;
            }
        }

        if (drew) {
            buffer.draw();
            com.mojang.blaze3d.systems.RenderSystem.enableCull(); // stock onRender postcondition
        }
    }

    /**
     * Exact physical held-item tip. Third person deliberately has no approximate fallback:
     * a portal-first pass waits until the physical held model supplied this frame's sample.
     */
    @Nullable
    public static Vec3 staffFocus(Player owner, float partialTick) {
        boolean mainHand = owner.getMainHandItem().getItem() instanceof PhysicsStaffItem
            || !(owner.getOffhandItem().getItem() instanceof PhysicsStaffItem);
        boolean firstPerson = owner.isLocalPlayer()
            && Minecraft.getInstance().options.getCameraType().isFirstPerson();
        if (!firstPerson) {
            return IplStaffFocus.capturedThirdPerson(owner, mainHand);
        }
        return PhysicsStaffClientHandler.getStaffFocusPos(owner, mainHand, partialTick);
    }

    /** Draw any physical run in its world; IP clips it to the active portal aperture. */
    private static boolean shouldDrawInThisPass(
        IplStaffBeamRoutes.Run run, ClientLevel renderLevel
    ) {
        return run.dim().equals(renderLevel.dimension());
    }

    public static void clear() {
        FRAME_RUNS.clear();
        IplStaffFocus.clear();
        if (beamBuffer != null) {
            beamBuffer.close();
            beamBuffer = null;
        }
    }

    public static boolean isPhysicalBeamPass() {
        return PHYSICAL_BEAM_PASS.get();
    }

    public static IplStaffBeamRoutes.Run getActiveRun() {
        return ACTIVE_RUN.get();
    }

    private static void renderPhysicalBeam(
        PhysicsStaffClientHandler.PhysicsBeam beam, IplStaffBeamRoutes.Run run,
        PoseStack poseStack, SuperRenderTypeBuffer buffer, Vec3 camera, float partialTick
    ) {
        boolean previous = PHYSICAL_BEAM_PASS.get();
        PHYSICAL_BEAM_PASS.set(true);
        ACTIVE_RUN.set(run);
        try {
            ((IplPhysicsStaffBeamInvokerMixin) (Object) beam).ipl$render(
                run.vertices().get(0).point(),
                run.vertices().get(run.vertices().size() - 1).point(),
                poseStack, buffer, camera, partialTick
            );
        } finally {
            ACTIVE_RUN.remove();
            PHYSICAL_BEAM_PASS.set(previous);
        }
    }

    private static Player findPlayer(UUID id) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.getUUID().equals(id)) return minecraft.player;
        for (ClientLevel world : ClientWorldLoader.getClientWorlds()) {
            if (world.dimension().equals(RenderStates.originalPlayerDimension)) {
                Player rootPlayer = world.getPlayerByUUID(id);
                if (rootPlayer != null) return rootPlayer;
            }
        }
        Player player = minecraft.level == null ? null : minecraft.level.getPlayerByUUID(id);
        if (player != null) return player;
        for (ClientLevel world : ClientWorldLoader.getClientWorlds()) {
            player = world.getPlayerByUUID(id);
            if (player != null) return player;
        }
        return null;
    }

    /** Beam anchors are plot coordinates: resolve the owning sub-level across client levels. */
    public static ClientSubLevel findHostedSubLevel(Vec3 localAnchor) {
        return IplClientProjections.findSubLevelAtPlot(localAnchor);
    }
}
