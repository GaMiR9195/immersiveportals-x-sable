package ipl.sable.transit;

import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves transit groups through Sable's public sub-level connection contract.
 *
 * <p>Sable already owns the authoritative dependency graph. Every actor that connects two
 * sub-levels exposes the link through {@code sable$getConnectionDependencies()}, and Sable
 * traverses that graph with {@link SubLevelHelper#getConnectedChain(SubLevel)}. Keeping the
 * traversal here would create a second, incomplete definition of a joint: it could disagree
 * with Sable about flexible joints, ropes, or a mod-specific actor and it would require
 * expensive native graph scans while a body is touching a portal.
 */
final class IplTransitConnections {

    private IplTransitConnections() {}

    record Group(List<ServerSubLevel> members, boolean complete) {}

    /**
     * Returns connected chains that currently contain at least one active transit body.
     *
     * <p>The snapshot is rebuilt at a transit boundary. Attaching or detaching an actor is
     * therefore visible to the next boundary without leaving a stale group id behind.
     * A failed Sable traversal is incomplete and cannot authorize a partial flip.
     */
    static List<Group> activeGroups(ServerLevel level, List<ServerSubLevel> all) {
        Map<UUID, ServerSubLevel> live = new LinkedHashMap<>();
        for (ServerSubLevel subLevel : all) {
            if (!subLevel.isRemoved() && subLevel.getLevel() == level) {
                live.put(subLevel.getUniqueId(), subLevel);
            }
        }

        List<ServerSubLevel> seeds = live.values().stream()
            .filter(IplTransitConnections::hasActiveTransit)
            .sorted(Comparator.comparing(subLevel -> subLevel.getUniqueId().toString()))
            .toList();

        Set<UUID> visited = new HashSet<>();
        List<Group> groups = new ArrayList<>();
        for (ServerSubLevel seed : seeds) {
            if (visited.contains(seed.getUniqueId())) continue;

            Map<UUID, ServerSubLevel> members = new LinkedHashMap<>();
            boolean complete = true;
            try {
                for (SubLevel connected : SubLevelHelper.getConnectedChain(seed)) {
                    if (!(connected instanceof ServerSubLevel serverSubLevel)
                        || serverSubLevel.isRemoved()
                        || serverSubLevel.getLevel() != level
                        || live.get(serverSubLevel.getUniqueId()) != serverSubLevel) {
                        complete = false;
                        continue;
                    }
                    members.putIfAbsent(serverSubLevel.getUniqueId(), serverSubLevel);
                }
            } catch (RuntimeException | LinkageError ignored) {
                complete = false;
            }

            // Keep a failed seed visible, but never mistake a failed traversal for a solo
            // component. The controller will retain its projection until the next boundary.
            members.putIfAbsent(seed.getUniqueId(), seed);
            members.values().forEach(subLevel -> visited.add(subLevel.getUniqueId()));

            List<ServerSubLevel> ordered = new ArrayList<>(members.values());
            ordered.sort(Comparator.comparing(subLevel -> subLevel.getUniqueId().toString()));
            groups.add(new Group(List.copyOf(ordered), complete));
        }
        return List.copyOf(groups);
    }

    private static boolean hasActiveTransit(ServerSubLevel subLevel) {
        IplPieceTracker.Body body = IplPieceTracker.tracked(subLevel);
        return body != null && body.hasSessions();
    }
}