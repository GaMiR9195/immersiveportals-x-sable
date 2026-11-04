package ipl.sable.mixin;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.physics.impl.rapier.constraint.RapierConstraintHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.simulated_team.simulated.config.server.physics.SimPhysics;
import dev.simulated_team.simulated.service.SimConfigService;
import ipl.sable.duck.IplStaffDragSessionControl;
import ipl.sable.transit.IplGrabChain;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.UUID;

/**
 * Simulated's original motor gains, target and per-substep joint lifecycle; only the
 * target is mapped through the grab chain into the body's coordinate chart.
 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler$DragSession", remap = false)
public abstract class StaffDragSessionMixin implements IplStaffDragSessionControl {

    @Shadow @Final private UUID playerUUID;
    @Shadow @Final private Vector3d plotAnchor;
    @Shadow @Final private Vector3d playerRelativeGoal;
    @Shadow @Final private Vector3d localGoal;
    @Shadow @Final private Quaterniond orientation;
    @Shadow @Final private ServerSubLevel subLevel;
    @Shadow private PhysicsConstraintHandle constraint;
    @Shadow private void attachConstraint(SubLevelPhysicsSystem physicsSystem) {}

    @Unique
    private final Vector3d ipl$playerEyeInterpolationStart = new Vector3d();
    @Unique
    private boolean ipl$hasPlayerEyeInterpolationStart;

    /**
     * @author IPL-Sable
     * @reason Preserve stock motor behavior while mapping the cursor through the grab chain.
     */
    @Overwrite
    private void physicsTick(SubLevelPhysicsSystem physicsSystem) {
        if (this.subLevel.isRemoved()) return;

        // Preserve Simulated's substep lifecycle, including its fresh motor impulse state.
        // Keeping and retargeting a warmed-up joint is NOT behaviorally equivalent.
        this.ipl$removeConstraint();
        this.attachConstraint(physicsSystem);
        // Якоря ставятся сразу при создании: addFreeConstraint создаёт сустав с НУЛЕВЫМИ
        // якорями, а точку хвата (plotAnchor − центр масс) пишет только joints::tick внутри
        // Rapier3D.step. Слитый шаг Atlas «вооружён» только у одного уровня, и сустав,
        // пересоздаваемый каждый подшаг, в остальных измерениях стоял бы в центре масс.
        // Натив обновляет суставы всех карт (joints::tick_all); установка здесь делает пивот
        // верным и со старой DLL.
        this.ipl$setMotorFrame(physicsSystem.getPipeline());

        ServerPlayer player = this.subLevel.getLevel().getServer().getPlayerList().getPlayer(this.playerUUID);
        if (player == null || this.constraint == null) return;

        SimPhysics config = SimConfigService.INSTANCE.server().physics;
        // addFreeConstraint(..., wakeUp=true) already wakes the grabbed body,
        // just as the original recreate-per-substep lifecycle does.
        for (ConstraintJointAxis axis : ConstraintJointAxis.ANGULAR) {
            this.constraint.setMotor(axis, 0.0, config.physicsStaffAngularStiffness.getF(),
                config.physicsStaffAngularDamping.getF(), false, 0.0);
        }

        double partial = physicsSystem.getPartialPhysicsTick();
        double eyeX;
        double eyeY;
        double eyeZ;
        if (this.ipl$hasPlayerEyeInterpolationStart) {
            // IP's server teleport sets old/current player position to the same
            // destination point. Continue Simulated's normal substep lerp from the
            // portal-mapped old eye instead of skipping the remaining target motion.
            eyeX = Mth.lerp(partial, this.ipl$playerEyeInterpolationStart.x, player.getX());
            eyeY = Mth.lerp(partial, this.ipl$playerEyeInterpolationStart.y,
                player.getY() + player.getEyeHeight());
            eyeZ = Mth.lerp(partial, this.ipl$playerEyeInterpolationStart.z, player.getZ());
            if (partial >= 1.0) {
                this.ipl$hasPlayerEyeInterpolationStart = false;
            }
        } else {
            eyeX = Mth.lerp(partial, player.xOld, player.getX());
            eyeY = Mth.lerp(partial, player.yOld, player.getY()) + player.getEyeHeight();
            eyeZ = Mth.lerp(partial, player.zOld, player.getZ());
        }
        this.localGoal.set(this.playerRelativeGoal).add(eyeX, eyeY, eyeZ);

        // The absolute cursor point is in the PLAYER's world frame; fold it through the
        // grab chain (the event-sourced portal path between player and body) into the
        // body's parent frame, immediately before Simulated converts world space into
        // its constraint-local motor target.
        Vec3 goal = IplGrabChain.mapGoal(
            player, this.subLevel, new Vec3(
                this.localGoal.x, this.localGoal.y, this.localGoal.z
            )
        );
        this.localGoal.set(goal.x, goal.y, goal.z);

        // Stock position spring: no derivative-of-packet target, artificial lead or
        // release impulse. Only the coordinate chart differs from Simulated.
        this.orientation.transformInverse(this.localGoal);

        this.constraint.setMotor(ConstraintJointAxis.LINEAR_X, this.localGoal.x,
            config.physicsStaffLinearStiffness.getF(), config.physicsStaffLinearDamping.getF(), false, 0.0);
        this.constraint.setMotor(ConstraintJointAxis.LINEAR_Y, this.localGoal.y,
            config.physicsStaffLinearStiffness.getF(), config.physicsStaffLinearDamping.getF(), false, 0.0);
        this.constraint.setMotor(ConstraintJointAxis.LINEAR_Z, this.localGoal.z,
            config.physicsStaffLinearStiffness.getF(), config.physicsStaffLinearDamping.getF(), false, 0.0);
    }

    /**
     * Parent flip runs after the current physics tick. Without this immediate reframe, the
     * existing native joint retains its source-frame target until the next substep and pulls
     * the fully crossed body back once: the visible handoff jerk on every portal, including
     * portals with identity rotation.
     */
    @Override
    public void ipl$reframeAfterTransit(Portal portal) {
        if (this.subLevel.isRemoved()) return;

        // localGoal is the live motor target in the old orientation basis. Recover its world
        // point, map it through the same portal used for the body, then encode it in the new
        // basis before another solver substep can see a stale target.
        Vector3d target = new Vector3d(this.localGoal);
        this.orientation.transform(target);
        Vec3 mapped = portal.transformPoint(
            new Vec3(target.x, target.y, target.z));
        DQuaternion rotation = portal.getRotationD();
        if (rotation != null) {
            this.orientation.set(new Quaterniond(rotation.x, rotation.y, rotation.z, rotation.w)
                .mul(this.orientation));
        }
        this.localGoal.set(mapped.x, mapped.y, mapped.z);
        this.orientation.transformInverse(this.localGoal);

        // Keep the live soft grip through the frame boundary. Removing it here is earlier
        // than Simulated's normal per-substep lifecycle: Rapier then enters the next tick
        // with no joint/island state and the body is briefly governed only by its carried
        // velocity before CPS grabs it again. That is the tiny slow-down/re-acceleration
        // visible at portal exit when the body is already trailing a moving soft target.
        //
        // Reframe both joint endpoints and its target instead. The body pose, target,
        // orientation and velocity have all crossed through the same isometry, so the
        // spring extension and its derivative are unchanged. The next ordinary CPS
        // physicsTick may then replace this joint at its usual point, exactly as stock does.
        if (this.constraint == null || !this.constraint.isValid()) return;
        ServerSubLevelContainer container = SubLevelContainer.getContainer(this.subLevel.getLevel());
        if (container == null) return;
        this.ipl$setMotorFrame(container.physicsSystem().getPipeline());

        SimPhysics config = SimConfigService.INSTANCE.server().physics;
        this.constraint.setMotor(ConstraintJointAxis.LINEAR_X, this.localGoal.x,
            config.physicsStaffLinearStiffness.getF(), config.physicsStaffLinearDamping.getF(), false, 0.0);
        this.constraint.setMotor(ConstraintJointAxis.LINEAR_Y, this.localGoal.y,
            config.physicsStaffLinearStiffness.getF(), config.physicsStaffLinearDamping.getF(), false, 0.0);
        this.constraint.setMotor(ConstraintJointAxis.LINEAR_Z, this.localGoal.z,
            config.physicsStaffLinearStiffness.getF(), config.physicsStaffLinearDamping.getF(), false, 0.0);
    }

    /**
     * Keep the stored cursor vector continuous when the dragging player crosses a portal.
     */
    @Override
    public void ipl$reframePlayerGoal(
        Portal portal,
        Vec3 previousEyePosition
    ) {
        Vec3 mapped = portal.transformLocalVecNonScale(
            new Vec3(
                this.playerRelativeGoal.x, this.playerRelativeGoal.y, this.playerRelativeGoal.z
            )
        );
        this.playerRelativeGoal.set(mapped.x, mapped.y, mapped.z);

        Vec3 mappedPreviousEye =
            portal.transformPoint(previousEyePosition);
        this.ipl$playerEyeInterpolationStart.set(
            mappedPreviousEye.x, mappedPreviousEye.y, mappedPreviousEye.z
        );
        this.ipl$hasPlayerEyeInterpolationStart = true;
    }

    @Override
    public void ipl$removeConstraint() {
        PhysicsConstraintHandle handle = this.constraint;
        this.constraint = null;
        // Match Simulated's release semantics: remove the joint, without changing body velocity.
        if (handle != null) handle.remove();
    }

    /**
     * Rebase the current soft joint without changing its identity or dropping the grip.
     * FreeConstraintConfiguration puts the world basis on endpoint 1 and the clicked
     * plot-local anchor on endpoint 2.
     */
    private void ipl$setMotorFrame(PhysicsPipeline pipeline) {
        if (!(this.constraint instanceof RapierConstraintHandle handle)) return;
        long scene = ((RapierPipelineAccessor) pipeline).ipl$sceneHandle();
        long joint = ((RapierConstraintHandleAccessor) handle).ipl$getNativeHandle();
        Rapier3D.setConstraintFrame(
            scene, joint, 0,
            0.0, 0.0, 0.0,
            this.orientation.x, this.orientation.y, this.orientation.z, this.orientation.w
        );
        Rapier3D.setConstraintFrame(
            scene, joint, 1,
            this.plotAnchor.x, this.plotAnchor.y, this.plotAnchor.z,
            0.0, 0.0, 0.0, 1.0
        );
    }

    /**
     * Simulated removes sessions without stopDragging when the player logs off or stops
     * holding the staff; the grab chain must die with its session, never outlive it.
     */
    @Inject(
        method = "onRemoved", at = @At("HEAD")
    )
    private void ipl$endChainOnRemoval(
        CallbackInfo ci
    ) {
        this.ipl$removeConstraint();
        IplGrabChain.end(this.subLevel.getLevel().getServer(), this.playerUUID);
    }
}
