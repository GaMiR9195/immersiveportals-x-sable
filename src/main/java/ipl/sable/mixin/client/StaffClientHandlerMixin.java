package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler;
import ipl.sable.client.IplClientProjections;
import ipl.sable.client.IplStaffBeamRoutes;
import ipl.sable.client.IplStraddleStaffPick;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

import java.util.UUID;

/**
 * Клиентский обработчик посоха: выбор цели через цепочку порталов и фокус камеры.
 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler", remap = false)
public abstract class StaffClientHandlerMixin {

    // ---- Захват через порталы ----
    // Replace Simulated's one-world pick at source. The handler receives the exact plot-coordinate
    // hit Sable expects, while the capture state records portal chain before session construction.

    @WrapOperation(
        method = "onItemUsed",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;pick(DFZ)Lnet/minecraft/world/phys/HitResult;"
        )
    )
    private HitResult ipl$portalPick(
        LocalPlayer player, double range, float tickDelta, boolean fluids, Operation<HitResult> original
    ) {
        HitResult local = original.call(player, range, tickDelta, fluids);
        HitResult projection = IplStraddleStaffPick.pickStraddleProjections(player, range, tickDelta);
        IplStraddleStaffPick.PortalTarget through =
            IplStraddleStaffPick.pickThroughPortals(player, range, tickDelta);
        // NEAREST-WINS. A through-portal target must never beat a candidate the player can
        // already see in their own world: otherwise a build next to (or under) the player
        // binds to the portal and is held through it. The comparison is between VISIBLE ray
        // lengths, the only frame in which a local hit and a through-portal hit are comparable.
        double localVisible = IplStraddleStaffPick.visibleRayLength(player, local, tickDelta);
        double projectionVisible = IplStraddleStaffPick.visibleRayLength(player, projection, tickDelta);
        // The vanilla pick ignores portals: it also finds the wall, ground or frame BEHIND a
        // doorway, which the player cannot see (the portal shows the other side there). Such a
        // hit is no candidate at all -- it used to beat every body seen through the portal
        // whenever anything stood behind the portal plane within reach, so grabbing and locking
        // through portals mostly failed. Same plane test as the through-portal walk.
        double portalAhead = IplStraddleStaffPick.firstPortalDistance(player, range, tickDelta);
        boolean localHidden = IplStraddleStaffPick.isBehindPortal(localVisible, portalAhead);
        if (localHidden) localVisible = Double.MAX_VALUE;
        if (IplStraddleStaffPick.isBehindPortal(projectionVisible, portalAhead)) projectionVisible = Double.MAX_VALUE;
        double nearestHere = Math.min(localVisible, projectionVisible);
        if (through != null && through.visibleDistance() <= nearestHere + 1.0e-4) {
            return through.hit();
        }
        // Rejected: pickThroughPortals records its result eagerly, so the speculative
        // capture has to be dropped here. Leaving it pending is what let
        // startDraggingSubLevel seed a portal chain for a body grabbed locally.
        if (through != null) IplStraddleStaffPick.discardTarget(through);
        if (projection != null && projectionVisible < localVisible) {
            IplStraddleStaffPick.rememberLocalProjection(player, projection);
            return projection;
        }
        if (localHidden) {
            // Nothing usable in front of the portal and nothing grabbable through it.
            return IplStraddleStaffPick.missAt(player, local, tickDelta);
        }
        IplStraddleStaffPick.rememberLocalProjection(player, local);
        return local;
    }

    /**
     * Simulated resolves the picked plot point in the player's own level only. A body seen
     * through a cross-dimension portal lives in another client level, so grab and lock fell
     * into "nothing hit". Use the exact object the pick resolved, then stock, then any level.
     */
    @WrapOperation(
        method = "onItemUsed",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/ActiveSableCompanion;getContainingClient(Lnet/minecraft/core/Position;)Ldev/ryanhcode/sable/sublevel/ClientSubLevel;"
        )
    )
    private ClientSubLevel ipl$pickedBodyInAnyLevel(
        ActiveSableCompanion helper, Position position, Operation<ClientSubLevel> original
    ) {
        Vec3 point = new Vec3(position.x(), position.y(), position.z());
        ClientSubLevel picked = IplStraddleStaffPick.pickedSubAt(point);
        if (picked != null) return picked;
        ClientSubLevel own = original.call(helper, position);
        return own != null ? own : IplClientProjections.findSubLevelAtPlot(point);
    }

    /**
     * A LOCK is a pick too. Simulated only seeds portal state on the drag path, so a lock
     * placed through a portal published no chain and its beam was drawn straight to the
     * body's raw world position. Seeding the same chain here makes "fixate through a
     * portal" behave optically identically to "grab through a portal".
     */
    @Inject(method = "lockSubLevel", at = @At("HEAD"))
    private void ipl$captureLockPath(
        SubLevel sub, Vec3 hitLocation,
        LocalPlayer player, InteractionHand hand, CallbackInfo ci
    ) {
        if (sub instanceof ClientSubLevel clientSub) {
            IplStraddleStaffPick.beginLock(clientSub);
        }
    }

    @Inject(method = "startDraggingSubLevel", at = @At("HEAD"))
    private void ipl$capturePortalPath(
        SubLevel sub, BlockPos blockPos, LocalPlayer player, InteractionHand hand, CallbackInfo ci
    ) {
        if (sub instanceof ClientSubLevel clientSub) {
            IplStraddleStaffPick.beginDrag(clientSub);
        }
    }

    /**
     * After Simulated built the drag session (with its native-pose distance), replace the
     * hold distance with the true visible pick distance for through-portal / projection grabs.
     * Fixes the maxed-out scroll on a cross-dimension grab and the wrong hold point on an image.
     */
    @Inject(method = "startDraggingSubLevel", at = @At("TAIL"))
    private void ipl$applyGrabDistance(
        SubLevel sub, BlockPos blockPos, LocalPlayer player, InteractionHand hand, CallbackInfo ci
    ) {
        IplStraddleStaffPick.applyGrabDistance(
            (PhysicsStaffClientHandler) (Object) this, sub);
    }

    @Inject(method = "stopDragging", at = @At("HEAD"))
    private void ipl$clearCaptureBeforeStop(CallbackInfo ci) {
        IplStraddleStaffPick.clearDragTargets();
    }

    /**
     * Beam node density derives from the length passed here: stock computes it with
     * {@code distanceSquaredWithSubLevels} on raw endpoints, which is cross-frame garbage
     * for a through-portal grab (wrong node count from the first frame). Seed the TRUE
     * physical length instead — the resolved route length when one exists, else the pick
     * ray's measured length — and bind the beam to its owner for the per-tick length feed.
     */
    @WrapOperation(
        method = "updateBeam",
        at = @At(
            value = "NEW",
            target = "dev/simulated_team/simulated/content/physics_staff/PhysicsStaffClientHandler$PhysicsBeam"
        )
    )
    private PhysicsStaffClientHandler.PhysicsBeam ipl$seedBeamLength(
        Vec3 start, Vec3 end, double length,
        Operation<PhysicsStaffClientHandler.PhysicsBeam> original,
        Level level, UUID uuid,
        Vec3 startArg, Vec3 endArg
    ) {
        double best = IplStaffBeamRoutes.knownLength(uuid);
        if (Double.isNaN(best)) {
            ClientSubLevel sub = IplClientProjections.findSubLevelAtPlot(end);
            if (sub != null) {
                best = IplStraddleStaffPick.pickDistance(sub.getUniqueId());
            }
        }
        PhysicsStaffClientHandler.PhysicsBeam beam =
            original.call(start, end, Double.isNaN(best) ? length : best);
        IplStaffBeamRoutes.registerBeamOwner(beam, uuid);
        return beam;
    }

    // ---- Фокус камеры ----
    // Preserve Simulated's focus math; IP's entity-visibility hook is not a camera mode.

    @WrapOperation(method = "getStaffFocusPos", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;getMainCamera()Lnet/minecraft/client/Camera;"), require = 1)
    private static Camera ipl$rootCamera(GameRenderer renderer, Operation<Camera> original) {
        return WorldRenderInfo.isRendering() && RenderStates.originalCamera != null
            ? RenderStates.originalCamera : original.call(renderer);
    }

    @WrapOperation(method = "getStaffFocusPos", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Camera;isDetached()Z"), require = 1)
    private static boolean ipl$physicalCameraMode(Camera camera, Operation<Boolean> original) {
        // MixinCamera.onIsThirdPerson returns true to show players THROUGH portals,
        // even on originalCamera. Calling it here selected the body-space origin
        // and cached that wrong origin for the entire physical beam frame.
        return !Minecraft.getInstance().options.getCameraType().isFirstPerson();
    }

    @Inject(method = "onRender", at = @At("HEAD"), cancellable = true, require = 1)
    private void ipl$ownedBeamPass(PoseStack poses, CallbackInfo ci) {
        // The NeoForge stage renders every beam. Do not run stock's global Catnip
        // draw() here: it can flush another world's pending selection effects.
        ci.cancel();
    }
}
