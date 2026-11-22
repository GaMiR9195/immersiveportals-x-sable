package ipl.sable.client;

import ipl.sable.transit.IplContraptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Create collides entities with the contraptions of the player's level only. A contraption whose
 * body reaches into that level through a portal lives in another level: its pieces here are
 * collided after Create's pass (see {@code IplContraptionCollision}).
 */
public final class IplContraptionCollisionClient {

    private IplContraptionCollisionClient() {}

    public static void collideFromOtherLevels(Level playerLevel) {
        if (playerLevel != Minecraft.getInstance().level || IplStraddleSessionStore.isIdle()) return;
        for (ClientLevel level : IplClientProjections.clientLevels()) {
            if (level == playerLevel) continue;
            for (Entity entity : level.entitiesForRendering()) {
                if (entity.isAlive() && IplContraptions.isContraption(entity)) IplContraptions.collideEntities(entity);
            }
        }
    }
}
