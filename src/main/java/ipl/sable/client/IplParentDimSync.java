package ipl.sable.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.dim.SableSubLevelDimension;
import ipl.sable.duck.IplSubLevelDuck;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.ClientWorldLoader;

import java.util.UUID;

/**
 * Client receiver for the hosted sub-level parent-dim stamp, invoked via IP's
 * {@code McRemoteProcedureCall} right after each full sync of a hosted sub-level
 * (see {@code SableCrossDimTrackingMixin}'s bootstrap).
 *
 * <p>The client-side {@code ClientSubLevel} is allocated in the sublevels-dim container by
 * the redirected start-tracking packet; its duck {@code parentLevel} defaults to the hosting
 * level. This call points it at the actual parent {@code ClientLevel} so the renderer and
 * client-side collision know which dimension the airship appears in.
 */
public final class IplParentDimSync {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-parent-sync");

    private IplParentDimSync() {}

    public static final class RemoteCallables {

        public static void setParent(String subLevelUuid, String parentDimId) {
            try {
                ClientLevel hosting = ClientWorldLoader.getWorld(SableSubLevelDimension.SUBLEVELS);
                SubLevelContainer container = SubLevelContainer.getContainer((Level) hosting);
                if (container == null) {
                    LOG.warn("[IPL-PARENT-SYNC] sublevels client world has no container");
                    return;
                }

                SubLevel subLevel = container.getSubLevel(UUID.fromString(subLevelUuid));
                if (subLevel == null) {
                    // Ordering should guarantee the sub-level exists (RPC is sent after the
                    // bundled full sync); a miss means tracking already stopped again.
                    LOG.warn("[IPL-PARENT-SYNC] no hosted sub-level {} (parent {})",
                        subLevelUuid, parentDimId);
                    return;
                }

                ResourceKey<Level> parentKey = ResourceKey.create(
                    Registries.DIMENSION, ResourceLocation.parse(parentDimId));
                ClientLevel parent = ClientWorldLoader.getWorld(parentKey);

                IplSubLevelDuck duck = (IplSubLevelDuck) subLevel;
                duck.ipl$setParentLevel(parent);
                duck.ipl$setHostingLevel(hosting);

                LOG.info("[IPL-PARENT-SYNC] sub-level {} parent={} (client)",
                    subLevelUuid, parentDimId);
            } catch (Throwable t) {
                LOG.error("[IPL-PARENT-SYNC] failed to apply parent stamp for {}", subLevelUuid, t);
            }
        }
    }
}
