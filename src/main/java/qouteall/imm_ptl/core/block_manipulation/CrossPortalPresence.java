package qouteall.imm_ptl.core.block_manipulation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.mixin.common.mc_util.IEEntity_LevelCallback;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * Lets a server player act on the far side of a portal for the length of one action.
 *
 * <p>For the action the player is where its eyes appear when seen from the far side: in the
 * destination level, at the portal-transformed eye position, looking along the transformed view
 * direction, with its velocity transformed. Vanilla's own handling then runs unchanged and gets
 * everything right in the frame the target lives in: reach checks, knockback direction, sweep
 * attacks (they hit the target's neighbours, not entities at the same coordinates in the
 * player's level), enchantment effects, criteria, events of other mods.
 *
 * <p>The player is never really moved: its entity-section callback is detached for the action
 * (no section or tracking change, no chunk ticket), position, box, rotations and level are
 * restored exactly afterwards, and the velocity change of the action (an attack slows the
 * attacker) is carried back through the portal. Riding something on the far side is undone.
 * Server thread only.
 */
public final class CrossPortalPresence {
    private CrossPortalPresence() {}
    
    public static void actThrough(ServerPlayer player, ServerLevel world, Portal portal, Runnable action) {
        ServerLevel realLevel = player.serverLevel();
        Vec3 realPos = player.position();
        AABB realBox = player.getBoundingBox();
        float yRot = player.getYRot();
        float xRot = player.getXRot();
        float yRotO = player.yRotO;
        float xRotO = player.xRotO;
        float yHeadRot = player.getYHeadRot();
        float yBodyRot = player.yBodyRot;
        Vec3 velocity = player.getDeltaMovement();
        EntityInLevelCallback callback = ((IEEntity_LevelCallback) player).ip_getLevelCallback();
        
        double eyeHeight = player.getEyeHeight();
        Vec3 virtualEye = portal.transformPoint(player.getEyePosition());
        Vec3 look = portal.transformLocalVecNonScale(player.getViewVector(1.0f));
        float virtualYRot = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float virtualXRot = (float) Math.toDegrees(-Math.asin(Mth.clamp(look.y, -1.0, 1.0)));
        
        player.setLevelCallback(EntityInLevelCallback.NULL);
        try {
            if (world != realLevel) player.setServerLevel(world);
            player.setPos(virtualEye.x, virtualEye.y - eyeHeight, virtualEye.z);
            player.setYRot(virtualYRot);
            player.setXRot(virtualXRot);
            player.yRotO = virtualYRot;
            player.xRotO = virtualXRot;
            player.setYHeadRot(virtualYRot);
            player.yBodyRot = virtualYRot;
            player.setDeltaMovement(portal.transformLocalVecNonScale(velocity));
            
            action.run();
        }
        finally {
            Vec3 velocityAfter = player.getDeltaMovement();
            if (player.serverLevel() != realLevel) player.setServerLevel(realLevel);
            player.setPosRaw(realPos.x, realPos.y, realPos.z);
            player.setBoundingBox(realBox);
            player.setYRot(yRot);
            player.setXRot(xRot);
            player.yRotO = yRotO;
            player.xRotO = xRotO;
            player.setYHeadRot(yHeadRot);
            player.yBodyRot = yBodyRot;
            player.setDeltaMovement(portal.inverseTransformLocalVecNonScale(velocityAfter));
            player.setLevelCallback(callback);
            
            if (player.getVehicle() != null && player.getVehicle().level() != realLevel) {
                player.stopRiding();
            }
        }
    }
}
