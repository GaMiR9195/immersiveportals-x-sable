package ipl.sable.client;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.duck.IplClientContainerAdoptDuck;
import ipl.sable.mixin.client.IplLevelPlotContainerAccessor;
import ipl.sable.mixin.client.IplSubLevelLevelAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parent-flip client migration: move an EXISTING {@link ClientSubLevel} — plot, chunks,
 * interpolator timeline and compiled render data intact — from one client level's
 * container into another's.
 *
 * <p>This is the client half of a hosted transit under the stock-client model: the ship
 * changes dimension, so its client object must change containers. Adopting in place keeps
 * the crossing seamless (the compiled section geometry keeps drawing without a rebuild);
 * the follow-up tracking re-sync then replaces the plot's chunk objects with copies bound
 * to the new level, restoring per-block light and block-entity level references.
 *
 * <p>Chunk and block-entity objects are deliberately NOT touched here — between the adopt
 * and the re-sync they still reference the old level, which only affects transient
 * client-side reads and is healed by the re-sync's chunk packets.
 */
public final class IplClientAdopt {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-client-adopt");

    private IplClientAdopt() {}

    /**
     * Move {@code sub} into {@code targetLevel}'s container. No-op when already there.
     *
     * @return true when the ship now lives in {@code targetLevel}'s container.
     */
    public static boolean adoptInto(ClientSubLevel sub, ClientLevel targetLevel) {
        Level currentLevel = sub.getLevel();
        if (currentLevel == targetLevel) {
            return true;
        }
        SubLevelContainer target = SubLevelContainer.getContainer((Level) targetLevel);
        SubLevelContainer source = currentLevel == null
            ? null : SubLevelContainer.getContainer(currentLevel);
        if (!(target instanceof ClientSubLevelContainer)) {
            LOG.warn("[IPL-ADOPT] target level {} has no client container",
                targetLevel.dimension().location());
            return false;
        }

        try {
            if (source instanceof IplClientContainerAdoptDuck sourceDuck) {
                sourceDuck.ipl$detachKeepingState(sub);
                // Flywheel lighting scene ids are per container — release ours and let the
                // target container assign a fresh one lazily.
                int scene = sub.getLightingSceneId();
                if (scene > 0 && source instanceof ClientSubLevelContainer sourceClient) {
                    sourceClient.freeLightingScene(scene);
                    sub.setLightingSceneId(-1);
                }
            }

            ((IplSubLevelLevelAccessor) (Object) sub).ipl$setLevel(targetLevel);
            ((IplLevelPlotContainerAccessor) (Object) sub.getPlot()).ipl$setContainer(target);
            ((IplClientContainerAdoptDuck) target).ipl$attachExisting(sub);

            LOG.info("[IPL-ADOPT] {} adopted {} -> {}", sub.getUniqueId(),
                currentLevel == null ? "?" : currentLevel.dimension().location(),
                targetLevel.dimension().location());
            return true;
        } catch (Throwable t) {
            LOG.error("[IPL-ADOPT] failed to adopt {} into {}", sub.getUniqueId(),
                targetLevel.dimension().location(), t);
            return false;
        }
    }
}
